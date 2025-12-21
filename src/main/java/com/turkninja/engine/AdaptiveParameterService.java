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
     * Get dynamic Stop Loss and Take Profit based on ATR (Phase 2)
     * Uses 2:1 risk-reward ratio
     * 
     * @param symbol     Trading symbol
     * @param barSeries  Price data
     * @param entryPrice Entry price
     * @param isLong     true for LONG, false for SHORT
     * @return StopAndTarget with SL and TP prices
     */
    public StopAndTarget getDynamicStopAndTarget(String symbol, BarSeries barSeries,
            double entryPrice, boolean isLong) {
        try {
            // Calculate ATR
            double atr = indicatorService.getATR(barSeries, atrPeriod);

            // Stop Loss = 1.5x ATR (reasonable buffer)
            double stopDistance = atr * 1.5;

            // Take Profit = 3x ATR (2:1 risk-reward ratio)
            double targetDistance = atr * 3.0;

            double stopLoss, takeProfit;

            if (isLong) {
                stopLoss = entryPrice - stopDistance;
                takeProfit = entryPrice + targetDistance;
            } else {
                stopLoss = entryPrice + stopDistance;
                takeProfit = entryPrice - targetDistance;
            }

            // Calculate percentages for logging
            double stopPercent = (stopDistance / entryPrice) * 100;
            double targetPercent = (targetDistance / entryPrice) * 100;

            logger.debug("📊 Dynamic TP/SL {} (ATR={:.4f}): SL={:.2f} (-{:.2f}%), TP={:.2f} (+{:.2f}%), R:R=2:1",
                    symbol, atr, stopLoss, stopPercent, takeProfit, targetPercent);

            return new StopAndTarget(stopLoss, takeProfit, atr, stopPercent, targetPercent);

        } catch (Exception e) {
            logger.error("Error calculating dynamic TP/SL for {}: {}", symbol, e.getMessage());

            // Fallback to fixed percentages
            double fallbackSL = isLong ? entryPrice * 0.98 : entryPrice * 1.02; // 2%
            double fallbackTP = isLong ? entryPrice * 1.04 : entryPrice * 0.96; // 4%

            return new StopAndTarget(fallbackSL, fallbackTP, 0.0, 2.0, 4.0);
        }
    }

    /**
     * Get position size multiplier based on MTF trend strength (Phase 3)
     * Strong trend = larger position, weak trend = smaller position
     * 
     * @param trendStrength MTF trend strength (0-100)
     * @return Multiplier (0.7-1.3)
     */
    public double getPositionSizeMultiplier(int trendStrength) {
        // Strong trend (>80): +30% position size
        if (trendStrength > 80) {
            return 1.3;
        }
        // Good trend (60-80): +20% position size
        else if (trendStrength > 60) {
            return 1.2;
        }
        // Medium trend (40-60): Normal position size
        else if (trendStrength > 40) {
            return 1.0;
        }
        // Weak trend (<40): -30% position size (reduce risk)
        else {
            return 0.7;
        }
    }

    /**
     * Get dynamic TP/SL with trend strength adjustment (Phase 3)
     * Strong trend = wider TP for better profit capture
     * 
     * @param symbol        Trading symbol
     * @param barSeries     Price data
     * @param entryPrice    Entry price
     * @param isLong        true for LONG, false for SHORT
     * @param trendStrength MTF trend strength (0-100)
     * @return StopAndTarget with adjusted TP
     */
    public StopAndTarget getDynamicStopAndTargetWithTrend(String symbol, BarSeries barSeries,
            double entryPrice, boolean isLong,
            int trendStrength) {
        try {
            // Calculate base ATR levels
            double atr = indicatorService.getATR(barSeries, atrPeriod);

            // Stop Loss = 1.5x ATR (unchanged)
            double stopDistance = atr * 1.5;

            // Take Profit = 3x ATR base, adjusted by trend strength
            double baseTpMultiplier = 3.0;
            double tpMultiplier = baseTpMultiplier;

            // Strong trend: expand TP for better profit
            if (trendStrength > 80) {
                tpMultiplier = baseTpMultiplier * 1.5; // 4.5x ATR (+50%)
            } else if (trendStrength > 60) {
                tpMultiplier = baseTpMultiplier * 1.3; // 3.9x ATR (+30%)
            } else if (trendStrength < 40) {
                // Weak trend: tighter TP (quick exit)
                tpMultiplier = baseTpMultiplier * 0.8; // 2.4x ATR (-20%)
            }

            double targetDistance = atr * tpMultiplier;

            double stopLoss, takeProfit;

            if (isLong) {
                stopLoss = entryPrice - stopDistance;
                takeProfit = entryPrice + targetDistance;
            } else {
                stopLoss = entryPrice + stopDistance;
                takeProfit = entryPrice - targetDistance;
            }

            // Calculate percentages for logging
            double stopPercent = (stopDistance / entryPrice) * 100;
            double targetPercent = (targetDistance / entryPrice) * 100;

            logger.debug("📊 Dynamic TP/SL {} (ATR={:.4f}, strength={}): SL={:.2f} (-{:.2f}%), TP={:.2f} (+{:.2f}%)",
                    symbol, atr, trendStrength, stopLoss, stopPercent, takeProfit, targetPercent);

            return new StopAndTarget(stopLoss, takeProfit, atr, stopPercent, targetPercent);

        } catch (Exception e) {
            logger.error("Error calculating trend-adjusted TP/SL for {}: {}", symbol, e.getMessage());

            // Fallback
            double fallbackSL = isLong ? entryPrice * 0.98 : entryPrice * 1.02;
            double fallbackTP = isLong ? entryPrice * 1.04 : entryPrice * 0.96;

            return new StopAndTarget(fallbackSL, fallbackTP, 0.0, 2.0, 4.0);
        }
    }

    /**
     * Get dynamic leverage based on ATR volatility (Phase 4)
     * High volatility → Lower leverage (reduce risk)
     * Low volatility → Higher leverage (maximize returns)
     * 
     * @param symbol       Trading symbol
     * @param barSeries    Price data
     * @param currentPrice Current price
     * @return Recommended leverage (5-20x)
     */
    public int getDynamicLeverage(String symbol, BarSeries barSeries, double currentPrice) {
        // Load configuration
        int highVolLeverage = Config.getInt("strategy.leverage.high.vol", 5);
        int mediumVolLeverage = Config.getInt("strategy.leverage.medium.vol", 10);
        int lowVolLeverage = Config.getInt("strategy.leverage.low.vol", 20);
        boolean dynamicLeverageEnabled = Config.getBoolean("strategy.dynamic.leverage.enabled", false);

        if (!dynamicLeverageEnabled) {
            // Return default leverage when disabled
            int defaultLeverage = Config.getInt("trade.leverage", 20);
            return defaultLeverage;
        }

        try {
            // Calculate ATR volatility
            double atr = indicatorService.getATR(barSeries, atrPeriod);
            double atrPercent = (atr / currentPrice) * 100;

            int leverage;
            String regime;

            if (atrPercent > highVolatilityThreshold) {
                // HIGH VOLATILITY (>2.5%): Use minimum leverage (5x)
                leverage = highVolLeverage;
                regime = "HIGH_VOL";
            } else if (atrPercent < lowVolatilityThreshold) {
                // LOW VOLATILITY (<0.5%): Use maximum leverage (20x)
                leverage = lowVolLeverage;
                regime = "LOW_VOL";
            } else {
                // MEDIUM VOLATILITY: Use moderate leverage (10x)
                leverage = mediumVolLeverage;
                regime = "MED_VOL";
            }

            logger.info("📊 Dynamic Leverage {} (ATR={:.2f}%): {}x ({})",
                    symbol, atrPercent, leverage, regime);

            return leverage;

        } catch (Exception e) {
            logger.error("Error calculating dynamic leverage for {}: {}", symbol, e.getMessage());
            // Fallback to safe default
            return Config.getInt("trade.leverage", 10);
        }
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

    /**
     * Stop Loss and Take Profit container (Phase 2)
     */
    public static class StopAndTarget {
        public final double stopLoss;
        public final double takeProfit;
        public final double atr;
        public final double stopPercent;
        public final double targetPercent;

        public StopAndTarget(double stopLoss, double takeProfit, double atr,
                double stopPercent, double targetPercent) {
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.atr = atr;
            this.stopPercent = stopPercent;
            this.targetPercent = targetPercent;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
