package com.turkninja.engine.criteria;

import com.turkninja.config.Config;
import com.turkninja.engine.AdaptiveParameterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.util.Map;

/**
 * RSI Momentum Filter
 * 
 * Checks if RSI is in the optimal range for trend continuation (not reversal).
 * Uses adaptive parameters based on volatility if enabled.
 * 
 * LONG: RSI 50-70 (momentum without overbought)
 * SHORT: RSI 30-50 (weakness without oversold)
 */
public class RSIMomentumFilter implements StrategyCriteria {

    private static final Logger logger = LoggerFactory.getLogger(RSIMomentumFilter.class);

    private final double baseRsiLongMin;
    private final double baseRsiLongMax;
    private final double baseRsiShortMin;
    private final double baseRsiShortMax;
    private final AdaptiveParameterService adaptiveParamService;
    // Removed BarSeries field

    public RSIMomentumFilter(AdaptiveParameterService adaptiveParamService) {
        this(adaptiveParamService,
                Double.parseDouble(Config.get("strategy.rsi.long.min", "50")),
                Double.parseDouble(Config.get("strategy.rsi.long.max", "70")),
                Double.parseDouble(Config.get("strategy.rsi.short.min", "30")),
                Double.parseDouble(Config.get("strategy.rsi.short.max", "50")));
    }

    public RSIMomentumFilter(AdaptiveParameterService adaptiveParamService,
            double longMin, double longMax, double shortMin, double shortMax) {
        this.adaptiveParamService = adaptiveParamService;
        this.baseRsiLongMin = longMin;
        this.baseRsiLongMax = longMax;
        this.baseRsiShortMin = shortMin;
        this.baseRsiShortMax = shortMax;

        logger.info("✅ RSI Momentum Filter initialized: LONG=[{}-{}], SHORT=[{}-{}], Adaptive={}",
                baseRsiLongMin, baseRsiLongMax, baseRsiShortMin, baseRsiShortMax,
                adaptiveParamService != null && adaptiveParamService.isEnabled());
    }

    @Override
    public boolean evaluate(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        boolean enabled = Boolean.parseBoolean(Config.get("strategy.rsi.filter.enabled", "true"));
        if (!enabled) return true;
        Double rsi = indicators.get("RSI");
        if (rsi == null) {
            logger.warn("⚠️ RSI indicator missing for {}, allowing trade", symbol);
            return true;
        }

        // Get adaptive parameters if enabled
        double rsiMin, rsiMax;
        if (adaptiveParamService != null && adaptiveParamService.isEnabled()) {
            AdaptiveParameterService.AdaptiveParams params = adaptiveParamService.getAdaptiveParams(symbol, series,
                    currentPrice);

            if (isLong) {
                rsiMin = params.rsiLongMin;
                rsiMax = params.rsiLongMax;
            } else {
                rsiMin = params.rsiShortMin;
                rsiMax = params.rsiShortMax;
            }
        } else {
            // Use base parameters
            if (isLong) {
                rsiMin = baseRsiLongMin;
                rsiMax = baseRsiLongMax;
            } else {
                rsiMin = baseRsiShortMin;
                rsiMax = baseRsiShortMax;
            }
        }

        boolean passes = rsi >= rsiMin && rsi <= rsiMax;

        if (!passes) {
            logger.debug("⏸️ {} {} filtered - RSI out of range ({:.0f} not in [{:.0f}-{:.0f}])",
                    symbol, isLong ? "LONG" : "SHORT", rsi, rsiMin, rsiMax);
        }

        return passes;
    }

    @Override
    public String getFilterName() {
        return "RSI Momentum";
    }

    @Override
    public String getFailureReason(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        Double rsi = indicators.get("RSI");
        if (rsi == null)
            return "RSI missing";

        double rsiMin = isLong ? baseRsiLongMin : baseRsiShortMin;
        double rsiMax = isLong ? baseRsiLongMax : baseRsiShortMax;

        return String.format("RSI %.0f outside range [%.0f-%.0f]", rsi, rsiMin, rsiMax);
    }
}
