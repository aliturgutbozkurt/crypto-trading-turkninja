package com.turkninja.engine;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.HashMap;
import java.util.Map;

/**
 * Calculator for Super Trend Indicator.
 * Super Trend is a trend-following indicator based on ATR (Average True Range).
 */
public class SuperTrendCalculator {

    /**
     * Calculates Super Trend values for the latest bar in the series.
     *
     * @param series     The bar series data
     * @param atrPeriod  The period for ATR calculation (e.g., 10)
     * @param multiplier The multiplier for ATR (e.g., 3.0)
     * @return Map containing:
     *         - "SUPER_TREND": The Super Trend value
     *         - "SUPER_TREND_DIRECTION": 1.0 for Bullish, -1.0 for Bearish
     */
    public static Map<String, Double> calculate(BarSeries series, int atrPeriod, double multiplier) {
        Map<String, Double> result = new HashMap<>();

        if (series.getBarCount() < atrPeriod + 1) {
            return result; // Not enough data
        }

        // 1. Calculate ATR
        double[] tr = new double[series.getBarCount()];
        double[] atr = new double[series.getBarCount()];

        // Calculate TR (True Range) for all bars
        for (int i = 1; i < series.getBarCount(); i++) {
            Bar current = series.getBar(i);
            Bar prev = series.getBar(i - 1);

            double high = current.getHighPrice().doubleValue();
            double low = current.getLowPrice().doubleValue();
            double prevClose = prev.getClosePrice().doubleValue();

            double hl = high - low;
            double hpc = Math.abs(high - prevClose);
            double lpc = Math.abs(low - prevClose);

            tr[i] = Math.max(hl, Math.max(hpc, lpc));
        }

        // Initial ATR (SMA of TR)
        double sumTr = 0;
        for (int i = 1; i <= atrPeriod; i++) {
            sumTr += tr[i];
        }
        atr[atrPeriod] = sumTr / atrPeriod;

        // Calculate remaining ATR values (Wilder's Smoothing)
        for (int i = atrPeriod + 1; i < series.getBarCount(); i++) {
            atr[i] = ((atr[i - 1] * (atrPeriod - 1)) + tr[i]) / atrPeriod;
        }

        // 2. Calculate Super Trend
        double[] upperBandBasic = new double[series.getBarCount()];
        double[] lowerBandBasic = new double[series.getBarCount()];
        double[] upperBandFinal = new double[series.getBarCount()];
        double[] lowerBandFinal = new double[series.getBarCount()];
        double[] superTrend = new double[series.getBarCount()];
        boolean[] trend = new boolean[series.getBarCount()]; // true = bullish, false = bearish

        // Initialize first values
        trend[atrPeriod] = true;
        upperBandFinal[atrPeriod] = 0;
        lowerBandFinal[atrPeriod] = 0;
        superTrend[atrPeriod] = 0;

        for (int i = atrPeriod + 1; i < series.getBarCount(); i++) {
            Bar current = series.getBar(i);
            Bar prev = series.getBar(i - 1);

            double high = current.getHighPrice().doubleValue();
            double low = current.getLowPrice().doubleValue();
            double close = current.getClosePrice().doubleValue();
            double prevClose = prev.getClosePrice().doubleValue();

            double hl2 = (high + low) / 2;

            // Basic Bands
            upperBandBasic[i] = hl2 + (multiplier * atr[i]);
            lowerBandBasic[i] = hl2 - (multiplier * atr[i]);

            // Final Upper Band
            if (upperBandBasic[i] < upperBandFinal[i - 1] || prevClose > upperBandFinal[i - 1]) {
                upperBandFinal[i] = upperBandBasic[i];
            } else {
                upperBandFinal[i] = upperBandFinal[i - 1];
            }

            // Final Lower Band
            if (lowerBandBasic[i] > lowerBandFinal[i - 1] || prevClose < lowerBandFinal[i - 1]) {
                lowerBandFinal[i] = lowerBandBasic[i];
            } else {
                lowerBandFinal[i] = lowerBandFinal[i - 1];
            }

            // Trend Direction
            boolean prevTrend = trend[i - 1];
            if (prevTrend) { // Previously Bullish
                if (close <= lowerBandFinal[i]) {
                    trend[i] = false; // Switch to Bearish
                } else {
                    trend[i] = true; // Remain Bullish
                }
            } else { // Previously Bearish
                if (close >= upperBandFinal[i]) {
                    trend[i] = true; // Switch to Bullish
                } else {
                    trend[i] = false; // Remain Bearish
                }
            }

            // Super Trend Value
            if (trend[i]) {
                superTrend[i] = lowerBandFinal[i];
            } else {
                superTrend[i] = upperBandFinal[i];
            }
        }

        int lastIndex = series.getEndIndex();
        result.put("SUPER_TREND", superTrend[lastIndex]);
        result.put("SUPER_TREND_DIRECTION", trend[lastIndex] ? 1.0 : -1.0);

        return result;
    }
}
