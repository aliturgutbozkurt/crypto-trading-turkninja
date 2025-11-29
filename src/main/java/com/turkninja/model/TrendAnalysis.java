package com.turkninja.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a detailed trend analysis with strength scoring
 * Used by MultiTimeframeService for enhanced trend detection
 */
public class TrendAnalysis {

    /** Trend direction: "BULLISH", "BEARISH", or "NEUTRAL" */
    private String direction;

    /** Trend strength score (0-100) */
    private int strength;

    /** Trends for individual timeframes (e.g., "5m" -> "BULLISH") */
    private Map<String, String> timeframeTrends;

    /** Whether all timeframes are aligned */
    private boolean aligned;

    public TrendAnalysis() {
        this.timeframeTrends = new HashMap<>();
        this.strength = 0;
        this.direction = "NEUTRAL";
        this.aligned = false;
    }

    public TrendAnalysis(String direction, int strength) {
        this();
        this.direction = direction;
        this.strength = strength;
    }

    // Getters and setters

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public int getStrength() {
        return strength;
    }

    public void setStrength(int strength) {
        this.strength = Math.max(0, Math.min(100, strength)); // Clamp to 0-100
    }

    public Map<String, String> getTimeframeTrends() {
        return timeframeTrends;
    }

    public void setTimeframeTrends(Map<String, String> timeframeTrends) {
        this.timeframeTrends = timeframeTrends;
    }

    public void addTimeframeTrend(String timeframe, String trend) {
        this.timeframeTrends.put(timeframe, trend);
    }

    public boolean isAligned() {
        return aligned;
    }

    public void setAligned(boolean aligned) {
        this.aligned = aligned;
    }

    /**
     * Check if trend is strong enough for trading
     * 
     * @param threshold Minimum strength threshold (0-100)
     * @return true if strength >= threshold
     */
    public boolean isStrongEnough(int threshold) {
        return this.strength >= threshold;
    }

    @Override
    public String toString() {
        return String.format("TrendAnalysis{direction=%s, strength=%d, aligned=%s, timeframes=%s}",
                direction, strength, aligned, timeframeTrends);
    }
}
