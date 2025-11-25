package com.turkninja.engine;

import java.util.TreeMap;
import java.util.Map;
import java.util.Optional;

/**
 * Represents a real-time order book (market depth) for a single symbol.
 * Uses TreeMap for efficient price-ordered storage and retrieval.
 */
public class OrderBook {
    private final String symbol;
    private final TreeMap<Double, Double> bids; // price DESC -> quantity
    private final TreeMap<Double, Double> asks; // price ASC -> quantity
    private long lastUpdateId;
    private long lastUpdateTime;

    public OrderBook(String symbol) {
        this.symbol = symbol;
        this.bids = new TreeMap<>((a, b) -> Double.compare(b, a)); // Descending order
        this.asks = new TreeMap<>(); // Ascending order
        this.lastUpdateId = 0;
        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Update bid (buy order) at specific price level
     */
    public synchronized void updateBid(double price, double quantity) {
        if (quantity == 0.0) {
            bids.remove(price);
        } else {
            bids.put(price, quantity);
        }
        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Update ask (sell order) at specific price level
     */
    public synchronized void updateAsk(double price, double quantity) {
        if (quantity == 0.0) {
            asks.remove(price);
        } else {
            asks.put(price, quantity);
        }
        this.lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Get total bid volume for top N levels
     */
    public synchronized double getTotalBidVolume(int levels) {
        return bids.values().stream()
                .limit(levels)
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    /**
     * Get total ask volume for top N levels
     */
    public synchronized double getTotalAskVolume(int levels) {
        return asks.values().stream()
                .limit(levels)
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    /**
     * Get best bid (highest buy price)
     */
    public synchronized Optional<Double> getBestBid() {
        return bids.isEmpty() ? Optional.empty() : Optional.of(bids.firstKey());
    }

    /**
     * Get best ask (lowest sell price)
     */
    public synchronized Optional<Double> getBestAsk() {
        return asks.isEmpty() ? Optional.empty() : Optional.of(asks.firstKey());
    }

    /**
     * Get spread (difference between best ask and best bid)
     */
    public synchronized double getSpread() {
        Optional<Double> bestBid = getBestBid();
        Optional<Double> bestAsk = getBestAsk();

        if (bestBid.isPresent() && bestAsk.isPresent()) {
            return bestAsk.get() - bestBid.get();
        }
        return 0.0;
    }

    /**
     * Calculate order book imbalance: (TotalBids - TotalAsks) / (TotalBids +
     * TotalAsks)
     * Returns value between -1.0 (all asks) and +1.0 (all bids)
     */
    public synchronized double getImbalance(int levels) {
        double totalBids = getTotalBidVolume(levels);
        double totalAsks = getTotalAskVolume(levels);

        if (totalBids + totalAsks == 0) {
            return 0.0;
        }

        return (totalBids - totalAsks) / (totalBids + totalAsks);
    }

    /**
     * Detect buy wall: Find price level with abnormally high bid volume
     * Returns price if wall detected, otherwise empty
     */
    public synchronized Optional<Double> detectBuyWall(double stdDevMultiplier) {
        if (bids.size() < 5) {
            return Optional.empty();
        }

        // Calculate mean and standard deviation of bid volumes
        double[] volumes = bids.values().stream()
                .limit(20)
                .mapToDouble(Double::doubleValue)
                .toArray();

        double mean = 0;
        for (double v : volumes) {
            mean += v;
        }
        mean /= volumes.length;

        double variance = 0;
        for (double v : volumes) {
            variance += Math.pow(v - mean, 2);
        }
        double stdDev = Math.sqrt(variance / volumes.length);

        double threshold = mean + (stdDevMultiplier * stdDev);

        // Find first price level with volume > threshold
        for (Map.Entry<Double, Double> entry : bids.entrySet()) {
            if (entry.getValue() > threshold) {
                return Optional.of(entry.getKey());
            }
        }

        return Optional.empty();
    }

    /**
     * Detect sell wall: Find price level with abnormally high ask volume
     */
    public synchronized Optional<Double> detectSellWall(double stdDevMultiplier) {
        if (asks.size() < 5) {
            return Optional.empty();
        }

        double[] volumes = asks.values().stream()
                .limit(20)
                .mapToDouble(Double::doubleValue)
                .toArray();

        double mean = 0;
        for (double v : volumes) {
            mean += v;
        }
        mean /= volumes.length;

        double variance = 0;
        for (double v : volumes) {
            variance += Math.pow(v - mean, 2);
        }
        double stdDev = Math.sqrt(variance / volumes.length);

        double threshold = mean + (stdDevMultiplier * stdDev);

        for (Map.Entry<Double, Double> entry : asks.entrySet()) {
            if (entry.getValue() > threshold) {
                return Optional.of(entry.getKey());
            }
        }

        return Optional.empty();
    }

    /**
     * Estimate slippage for a market order of given quantity
     * Returns percentage of price movement expected
     */
    public synchronized double estimateSlippage(String side, double quantityUSDT, double currentPrice) {
        TreeMap<Double, Double> book = side.equals("BUY") ? asks : bids;

        if (book.isEmpty()) {
            return 0.0;
        }

        double remainingUsdt = quantityUSDT;
        double totalBaseAssetQty = 0.0;

        for (Map.Entry<Double, Double> entry : book.entrySet()) {
            double price = entry.getKey();
            double availableBaseQty = entry.getValue();
            double availableUsdt = availableBaseQty * price;

            if (remainingUsdt <= availableUsdt) {
                // Fill the rest of the order at this level
                double baseQty = remainingUsdt / price;
                totalBaseAssetQty += baseQty;
                remainingUsdt = 0;
                break;
            } else {
                // Consume this entire level
                totalBaseAssetQty += availableBaseQty;
                remainingUsdt -= availableUsdt;
            }
        }

        if (remainingUsdt > 0) {
            // Order book not deep enough
            return 1.0; // 100% slippage (should prevent trade)
        }

        double avgExecutionPrice = quantityUSDT / totalBaseAssetQty;
        return Math.abs(avgExecutionPrice - currentPrice) / currentPrice;
    }

    public String getSymbol() {
        return symbol;
    }

    public synchronized long getLastUpdateId() {
        return lastUpdateId;
    }

    public synchronized void setLastUpdateId(long lastUpdateId) {
        this.lastUpdateId = lastUpdateId;
    }

    public synchronized long getLastUpdateTime() {
        return lastUpdateTime;
    }

    public synchronized int getBidLevels() {
        return bids.size();
    }

    public synchronized int getAskLevels() {
        return asks.size();
    }

    /**
     * Clear all order book data
     */
    public synchronized void clear() {
        bids.clear();
        asks.clear();
        lastUpdateId = 0;
    }
}
