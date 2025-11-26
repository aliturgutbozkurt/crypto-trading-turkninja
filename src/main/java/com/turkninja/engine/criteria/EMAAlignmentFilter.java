package com.turkninja.engine.criteria;

import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * EMA Alignment Filter
 * 
 * Checks if EMAs are properly aligned for the trade direction.
 * 
 * LONG (Bullish): Price > EMA21 > EMA50
 * SHORT (Bearish): Price < EMA21 < EMA50
 * 
 * Buffer: 0.7% tolerance to avoid fakeouts
 */
public class EMAAlignmentFilter implements StrategyCriteria {

    private static final Logger logger = LoggerFactory.getLogger(EMAAlignmentFilter.class);

    private final double bufferPercent;

    public EMAAlignmentFilter() {
        this.bufferPercent = Double.parseDouble(Config.get("strategy.ema.buffer.percent", "0.007"));
        logger.debug("✅ EMA Alignment Filter initialized: buffer={}%", bufferPercent * 100);
    }

    @Override
    public boolean evaluate(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        Double ema21 = indicators.get("EMA_21");
        Double ema50 = indicators.get("EMA_50");

        if (ema21 == null || ema50 == null) {
            logger.warn("⚠️ EMA indicators missing for {}, allowing trade", symbol);
            return true;
        }

        boolean passes;
        if (isLong) {
            // LONG: Price > EMA21 > EMA50 (with buffer)
            boolean priceAboveEma21 = currentPrice > ema21 * (1 + bufferPercent);
            boolean ema21AboveEma50 = ema21 > ema50 * (1 + bufferPercent);
            passes = priceAboveEma21 && ema21AboveEma50;

            if (!passes) {
                logger.debug("⏸️ {} LONG filtered - EMA alignment broken (Price:{}, EMA21:{}, EMA50:{})",
                        symbol, currentPrice, ema21, ema50);
            }
        } else {
            // SHORT: Price < EMA21 < EMA50 (with buffer)
            boolean priceBelowEma21 = currentPrice < ema21 * (1 - bufferPercent);
            boolean ema21BelowEma50 = ema21 < ema50 * (1 - bufferPercent);
            passes = priceBelowEma21 && ema21BelowEma50;

            if (!passes) {
                logger.debug("⏸️ {} SHORT filtered - EMA alignment broken (Price:{}, EMA21:{}, EMA50:{})",
                        symbol, currentPrice, ema21, ema50);
            }
        }

        return passes;
    }

    @Override
    public String getFilterName() {
        return "EMA Alignment (Trend Direction)";
    }

    @Override
    public String getFailureReason(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        Double ema21 = indicators.get("EMA_21");
        Double ema50 = indicators.get("EMA_50");

        if (ema21 == null || ema50 == null)
            return "EMA missing";

        if (isLong) {
            return String.format("Bullish alignment broken: Price=%.2f, EMA21=%.2f, EMA50=%.2f",
                    currentPrice, ema21, ema50);
        } else {
            return String.format("Bearish alignment broken: Price=%.2f, EMA21=%.2f, EMA50=%.2f",
                    currentPrice, ema21, ema50);
        }
    }
}
