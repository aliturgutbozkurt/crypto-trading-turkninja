package com.turkninja.engine.criteria;

import java.util.Map;

/**
 * Strategy Criteria Interface
 * 
 * Represents a single filter/condition in the trading strategy.
 * Implementing classes should check a specific technical condition
 * and return true if the condition passes, false otherwise.
 * 
 * This allows for modular, testable strategy components using
 * the Chain of Responsibility pattern.
 */
public interface StrategyCriteria {

    /**
     * Evaluate this criteria for a potential trade
     * 
     * @param symbol       Trading symbol (e.g., "ETHUSDT")
     * @param series       BarSeries data (for calculating slope, volume, etc.)
     * @param indicators   Map of calculated indicators (RSI, MACD, EMA, etc.)
     * @param currentPrice Current price of the asset
     * @param isLong       True for LONG filter, false for SHORT filter
     * @return true if criteria passes, false if filter blocks trade
     */
    boolean evaluate(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators, double currentPrice,
            boolean isLong);

    /**
     * Get human-readable name of this filter
     * Used for logging why a trade was blocked
     * 
     * @return Filter name (e.g., "ADX Trend Strength")
     */
    String getFilterName();

    /**
     * Get reason why filter failed (optional, for detailed logging)
     * Called only when evaluate() returns false
     * 
     * @param symbol       Trading symbol
     * @param series       BarSeries data
     * @param indicators   Indicators map
     * @param currentPrice Current price
     * @param isLong       Direction
     * @return Human-readable reason or null
     */
    default String getFailureReason(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice,
            boolean isLong) {
        return null; // Optional - can be overridden for detailed logging
    }
}
