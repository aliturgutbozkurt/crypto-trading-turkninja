package com.turkninja.engine.criteria;

import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * MACD Confirmation Filter
 * 
 * Checks if MACD aligns with trade direction for trend confirmation.
 * 
 * LONG: MACD > Signal Line (bullish)
 * SHORT: MACD < Signal Line (bearish)
 * 
 * Tolerance: Small buffer to avoid noise around crossovers
 */
public class MACDConfirmationFilter implements StrategyCriteria {

    private static final Logger logger = LoggerFactory.getLogger(MACDConfirmationFilter.class);

    private final double tolerance;

    public MACDConfirmationFilter() {
        this(Double.parseDouble(Config.get("strategy.macd.signal.tolerance", "0.00001")));
    }

    public MACDConfirmationFilter(double tolerance) {
        this.tolerance = tolerance;
        logger.info("✅ MACD Confirmation Filter initialized: tolerance={}", tolerance);
    }

    @Override
    public boolean evaluate(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        Double macd = indicators.get("MACD");
        Double macdSignal = indicators.get("MACD_SIGNAL");

        if (macd == null || macdSignal == null) {
            logger.warn("⚠️ MACD indicators missing for {}, allowing trade", symbol);
            return true;
        }

        boolean passes;
        if (isLong) {
            // LONG: MACD > Signal
            passes = macd > (macdSignal + tolerance);
            if (!passes) {
                logger.debug("⏸️ {} LONG filtered - MACD not bullish (MACD:{:.4f} <= Signal:{:.4f})",
                        symbol, macd, macdSignal);
            }
        } else {
            // SHORT: MACD < Signal
            passes = macd < (macdSignal - tolerance);
            if (!passes) {
                logger.debug("⏸️ {} SHORT filtered - MACD not bearish (MACD:{:.4f} >= Signal:{:.4f})",
                        symbol, macd, macdSignal);
            }
        }

        return passes;
    }

    @Override
    public String getFilterName() {
        return "MACD Confirmation";
    }

    @Override
    public String getFailureReason(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        Double macd = indicators.get("MACD");
        Double macdSignal = indicators.get("MACD_SIGNAL");

        if (macd == null || macdSignal == null)
            return "MACD missing";

        if (isLong) {
            return String.format("MACD %.6f <= Signal %.6f (not bullish)", macd, macdSignal);
        } else {
            return String.format("MACD %.6f >= Signal %.6f (not bearish)", macd, macdSignal);
        }
    }
}
