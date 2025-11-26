package com.turkninja.engine;

import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptive Parameter Service (Phase 1.1)
 * Dynamically adjusts strategy parameters based on market volatility (ATR)
 * High volatility → Stricter thresholds (avoid noise)
 * Low volatility → Wider thresholds (capture moves)
 */
public class AdaptiveParameterService {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveParameterService.class);

    private final IndicatorService indicatorService;

    // Cache: symbol → adaptive params
    private final Map<String, AdaptiveParams> paramsCache = new ConcurrentHashMap<>();
    private long lastUpdateTime = 0;
    private static final long CACHE_TTL_MS = 60 * 1000; // 1 minute cache

    // Configuration
    private final boolean enabled;
    private final double baseRsiLongMin;
    private final double baseRsiLongMax;
    private final double baseRsiShortMin;
    private final double baseRsiShortMax;
    private final int atrPeriod;

    // Volatility thresholds
    private final double highVolatilityThreshold; // ATR % above which is "high vol"
    private final double lowVolatilityThreshold; // ATR % below which is "low vol"

    public AdaptiveParameterService(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;

        // Load configuration
        this.enabled = Boolean.parseBoolean(Config.get("strategy.adaptive.enabled", "false"));
        this.baseRsiLongMin = Double.parseDouble(Config.get("strategy.rsi.long.min", "50"));
        this.baseRsiLongMax = Double.parseDouble(Config.get("strategy.rsi.long.max", "70"));
        this.baseRsiShortMin = Double.parseDouble(Config.get("strategy.rsi.short.min", "30"));
        this.baseRsiShortMax = Double.parseDouble(Config.get("strategy.rsi.short.max", "50"));
        this.atrPeriod = Integer.parseInt(Config.get("strategy.adaptive.atr.period", "14"));

        // Volatility thresholds (ATR as % of price)
        this.highVolatilityThreshold = Double.parseDouble(Config.get("strategy.adaptive.volatility.high", "2.0"));
        this.lowVolatilityThreshold = Double.parseDouble(Config.get("strategy.adaptive.volatility.low", "0.5"));

        logger.info("AdaptiveParameterService initialized: enabled={}, ATR period={}, High Vol={}%, Low Vol={}%",
                enabled, atrPeriod, highVolatilityThreshold, lowVolatilityThreshold);
    }

    /**
     * Get adaptive RSI parameters for a symbol
     *
     * @param symbol       Trading symbol
     * @param barSeries    Price data
     * @param currentPrice Current price
     * @return Adaptive parameters
     */
    public AdaptiveParams getAdaptiveParams(String symbol, BarSeries barSeries, double currentPrice) {
        if (!enabled) {
            // Return base parameters if disabled
            return new AdaptiveParams(
                    baseRsiLongMin, baseRsiLongMax,
                    baseRsiShortMin, baseRsiShortMax,
                    "DISABLED", 0.0);
        }

        // Check cache
        if (isCacheValid()) {
            AdaptiveParams cached = paramsCache.get(symbol);
            if (cached != null) {
                return cached;
            }
        }

        // Calculate and cache
        return calculateAdaptiveParams(symbol, barSeries, currentPrice);
    }

    /**
     * Calculate adaptive parameters based on ATR volatility
     */
    private AdaptiveParams calculateAdaptiveParams(String symbol, BarSeries barSeries, double currentPrice) {
        try {
            // Calculate ATR
            double atr = indicatorService.getATR(barSeries, atrPeriod);
            double atrPercent = (atr / currentPrice) * 100;

            // Determine volatility regime
            String regime;
            double rsiLongMin = baseRsiLongMin;
            double rsiLongMax = baseRsiLongMax;
            double rsiShortMin = baseRsiShortMin;
            double rsiShortMax = baseRsiShortMax;

            if (atrPercent > highVolatilityThreshold) {
                // HIGH VOLATILITY → Stricter thresholds (wait for deeper dips/higher rallies)
                regime = "HIGH";
                rsiLongMin -= 5; // e.g., 50 → 45 (wait for bigger dip)
                rsiLongMax += 5; // e.g., 70 → 75 (allow more overbought)
                rsiShortMin -= 5; // e.g., 30 → 25 (allow more oversold)
                rsiShortMax += 5; // e.g., 50 → 55 (wait for bigger rise before shorting)

            } else if (atrPercent < lowVolatilityThreshold) {
                // LOW VOLATILITY → Wider thresholds (reduce false signals in ranging market)
                regime = "LOW";
                rsiLongMin += 5; // e.g., 50 → 55 (less sensitive)
                rsiLongMax -= 5; // e.g., 70 → 65 (avoid late entries)
                rsiShortMin += 5; // e.g., 30 → 35
                rsiShortMax -= 5; // e.g., 50 → 45

            } else {
                // MEDIUM VOLATILITY → Use base parameters
                regime = "MEDIUM";
            }

            AdaptiveParams params = new AdaptiveParams(
                    rsiLongMin, rsiLongMax,
                    rsiShortMin, rsiShortMax,
                    regime, atrPercent);

            // Cache result
            paramsCache.put(symbol, params);
            lastUpdateTime = System.currentTimeMillis();

            logger.debug("📊 Adaptive {} (ATR={:.2f}%): Regime={}, RSI LONG=[{:.0f}-{:.0f}], SHORT=[{:.0f}-{:.0f}]",
                    symbol, atrPercent, regime, rsiLongMin, rsiLongMax, rsiShortMin, rsiShortMax);

            return params;

        } catch (Exception e) {
            logger.error("Error calculating adaptive params for {}: {}", symbol, e.getMessage());
            // Return base parameters on error
            return new AdaptiveParams(
                    baseRsiLongMin, baseRsiLongMax,
                    baseRsiShortMin, baseRsiShortMax,
                    "ERROR", 0.0);
        }
    }

    /**
     * Check if cache is still valid
     */
    private boolean isCacheValid() {
        return System.currentTimeMillis() - lastUpdateTime < CACHE_TTL_MS;
    }

    /**
     * Clear parameters cache (force recalculation)
     */
    public void clearCache() {
        paramsCache.clear();
        lastUpdateTime = 0;
        logger.info("Adaptive params cache cleared");
    }

    /**
     * Adaptive parameters container
     */
    public static class AdaptiveParams {
        public final double rsiLongMin;
        public final double rsiLongMax;
        public final double rsiShortMin;
        public final double rsiShortMax;
        public final String volatilityRegime; // "LOW", "MEDIUM", "HIGH"
        public final double atrPercent;

        public AdaptiveParams(double rsiLongMin, double rsiLongMax,
                double rsiShortMin, double rsiShortMax,
                String volatilityRegime, double atrPercent) {
            this.rsiLongMin = rsiLongMin;
            this.rsiLongMax = rsiLongMax;
            this.rsiShortMin = rsiShortMin;
            this.rsiShortMax = rsiShortMax;
            this.volatilityRegime = volatilityRegime;
            this.atrPercent = atrPercent;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
