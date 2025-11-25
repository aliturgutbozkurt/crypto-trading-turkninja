package com.turkninja.engine;

/**
 * Represents a trading signal with calculated score based on multiple criteria.
 * Used to rank and compare signals to select the best trading opportunities.
 */
public class SignalScore implements Comparable<SignalScore> {

    // Signal metadata
    public final String symbol;
    public final String side; // BUY or SELL
    public final double price;

    // Individual component scores
    public double rsiScore; // 0-25 points
    public double macdScore; // 0-25 points
    public double trendScore; // 0-20 points
    public double volumeScore; // 0-15 points
    public double btcScore; // 0-10 points
    public double depthScore; // 0-5 points

    // Total score (sum of all components)
    public double totalScore;

    // Signal timestamp
    public final long timestamp;

    public SignalScore(String symbol, String side, double price) {
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Calculate total score from all components
     */
    public void calculateTotalScore() {
        this.totalScore = rsiScore + macdScore + trendScore + volumeScore + btcScore + depthScore;
    }

    /**
     * Compare by total score (descending order - higher is better)
     */
    @Override
    public int compareTo(SignalScore other) {
        return Double.compare(other.totalScore, this.totalScore);
    }

    @Override
    public String toString() {
        return String.format("%s %s @ $%.4f | Score: %.1f (RSI:%.1f MACD:%.1f Trend:%.1f Vol:%.1f BTC:%.1f Depth:%.1f)",
                symbol, side, price, totalScore, rsiScore, macdScore, trendScore, volumeScore, btcScore, depthScore);
    }
}
