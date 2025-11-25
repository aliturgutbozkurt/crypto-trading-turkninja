package com.turkninja.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nadaraya-Watson Envelope Calculator
 * Uses kernel regression to create smooth, volatility-adaptive trend indicator
 * Provides dynamic support/resistance bands based on market volatility
 */
public class NadarayaWatsonCalculator {

    /**
     * Calculate Nadaraya-Watson Envelope
     * 
     * @param prices     Historical closing prices
     * @param lookback   Number of bars to look back
     * @param bandwidth  Kernel bandwidth (h) - controls smoothness (8-14)
     * @param multiplier Band width multiplier (2.0-3.0)
     * @return Map with middle, upper, lower bands and bandwidth percentage
     */
    public static Map<String, Double> calculateNWE(List<Double> prices, int lookback, double bandwidth,
            double multiplier) {
        Map<String, Double> result = new HashMap<>();

        if (prices == null || prices.size() < lookback) {
            result.put("NWE_MIDDLE", 0.0);
            result.put("NWE_UPPER", 0.0);
            result.put("NWE_LOWER", 0.0);
            result.put("NWE_BANDWIDTH_PERCENT", 0.0);
            return result;
        }

        int size = prices.size();
        int startIndex = Math.max(0, size - lookback);

        // Calculate Nadaraya-Watson regression for current point
        double yEst = nadarayaWatsonEstimate(prices, startIndex, size - 1, bandwidth);

        // Calculate standard deviation for band width
        double stdDev = calculateStdDev(prices, startIndex, yEst);

        // Create bands
        double middle = yEst;
        double upper = middle + (stdDev * multiplier);
        double lower = middle - (stdDev * multiplier);

        // Calculate bandwidth as percentage of price
        double bandwidthPercent = (upper - lower) / middle;

        result.put("NWE_MIDDLE", middle);
        result.put("NWE_UPPER", upper);
        result.put("NWE_LOWER", lower);
        result.put("NWE_BANDWIDTH_PERCENT", bandwidthPercent);

        return result;
    }

    /**
     * Nadaraya-Watson kernel regression estimate
     * Uses Gaussian kernel for weighting
     */
    private static double nadarayaWatsonEstimate(List<Double> prices, int startIndex, int targetIndex, double h) {
        double numerator = 0.0;
        double denominator = 0.0;

        for (int i = startIndex; i < prices.size(); i++) {
            double x = i - targetIndex;
            double weight = gaussianKernel(x / h);

            numerator += weight * prices.get(i);
            denominator += weight;
        }

        return denominator > 0 ? numerator / denominator : prices.get(targetIndex);
    }

    /**
     * Gaussian (Normal) kernel function
     * K(u) = (1/sqrt(2π)) * exp(-0.5 * u^2)
     */
    private static double gaussianKernel(double u) {
        return Math.exp(-0.5 * u * u) / Math.sqrt(2 * Math.PI);
    }

    /**
     * Calculate standard deviation of prices around regression line
     */
    private static double calculateStdDev(List<Double> prices, int startIndex, double mean) {
        double sumSquaredDiff = 0.0;
        int count = 0;

        for (int i = startIndex; i < prices.size(); i++) {
            double diff = prices.get(i) - mean;
            sumSquaredDiff += diff * diff;
            count++;
        }

        return count > 0 ? Math.sqrt(sumSquaredDiff / count) : 0.0;
    }

    /**
     * Determine price position relative to NWE bands
     * Returns: "ABOVE_UPPER", "BELOW_LOWER", "INSIDE"
     */
    public static String getPricePosition(double currentPrice, Map<String, Double> nwe) {
        double upper = nwe.get("NWE_UPPER");
        double lower = nwe.get("NWE_LOWER");

        if (currentPrice > upper) {
            return "ABOVE_UPPER";
        } else if (currentPrice < lower) {
            return "BELOW_LOWER";
        } else {
            return "INSIDE";
        }
    }

    /**
     * Check if bands are too narrow (choppy market filter)
     * Returns true if bandwidth is below minimum threshold
     */
    public static boolean isBandwidthTooNarrow(Map<String, Double> nwe, double minBandwidthPercent) {
        double bandwidthPercent = nwe.get("NWE_BANDWIDTH_PERCENT");
        return bandwidthPercent < minBandwidthPercent;
    }
}
