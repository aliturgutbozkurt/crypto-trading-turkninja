package com.turkninja.engine.criteria;

import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * ADX Trend Strength Filter
 * 
 * Blocks trades when ADX (Average Directional Index) is below threshold.
 * ADX < 25: Sideways/ranging market (no clear trend)
 * ADX > 25: Trending market (good for trading)
 * 
 * Purpose: Avoid 40-50% of false signals in sideways markets
 */
public class ADXTrendStrengthFilter implements StrategyCriteria {

    private static final Logger logger = LoggerFactory.getLogger(ADXTrendStrengthFilter.class);

    private final boolean enabled;
    private final double minStrength;

    public ADXTrendStrengthFilter() {
        this.enabled = Boolean.parseBoolean(Config.get("strategy.adx.enabled", "true"));
        this.minStrength = Double.parseDouble(Config.get("strategy.adx.min.strength", "25"));

        if (enabled) {
            logger.info("✅ ADX Filter initialized: minStrength={}", minStrength);
        }
    }

    @Override
    public boolean evaluate(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        if (!enabled) {
            return true; // Filter disabled, allow trade
        }

        Double adx = indicators.get("ADX");
        if (adx == null) {
            logger.warn("⚠️ ADX indicator missing for {}, allowing trade", symbol);
            return true; // Data missing, don't block
        }

        boolean passes = adx >= minStrength;

        if (!passes) {
            logger.info("⏸️ {} {} filtered - ADX too low ({:.2f} < {:.2f}) - Sideways market",
                    symbol, isLong ? "LONG" : "SHORT", adx, minStrength);
        }

        return passes;
    }

    @Override
    public String getFilterName() {
        return "ADX Trend Strength";
    }

    @Override
    public String getFailureReason(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        Double adx = indicators.get("ADX");
        if (adx == null)
            return "ADX missing";
        return String.format("ADX %.2f < %.2f (sideways market)", adx, minStrength);
    }
}
