package com.turkninja.engine;

import com.turkninja.config.Config;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing real-time order book data for multiple symbols.
 * Processes WebSocket depth updates and provides order book analytics.
 */
public class OrderBookService {
    private static final Logger logger = LoggerFactory.getLogger(OrderBookService.class);

    private final Map<String, OrderBook> orderBooks;
    private final boolean enabled;
    private final int depthLevels;
    private final double imbalanceThreshold;
    private final double maxSpreadPercent;
    private final double wallStdDevMultiplier;
    private final double maxSlippagePercent;
    private final boolean wallFilterEnabled;

    // CVD (Cumulative Volume Delta) tracking - Order Flow Analysis
    private final Map<String, Double> cumulativeVolumeDelta = new ConcurrentHashMap<>();
    private final Map<String, Double> previousBidVolume = new ConcurrentHashMap<>();
    private final Map<String, Double> previousAskVolume = new ConcurrentHashMap<>();

    public OrderBookService() {
        this.orderBooks = new ConcurrentHashMap<>();
        this.enabled = Config.getBoolean("orderbook.enabled", true);
        this.depthLevels = Config.getInt("orderbook.depth_levels", 20);
        // Load strategy specific config with fallback to generic config
        this.imbalanceThreshold = Config.getDouble("strategy.orderbook.min_imbalance",
                Config.getDouble("orderbook.imbalance_threshold", 0.2));
        this.wallFilterEnabled = Config.getBoolean("strategy.orderbook.wall_filter_enabled", true);

        this.maxSpreadPercent = Config.getDouble("orderbook.max_spread_percent", 0.001);
        this.wallStdDevMultiplier = Config.getDouble("orderbook.wall_stddev_multiplier", 2.0);
        this.maxSlippagePercent = Config.getDouble("orderbook.max_slippage_percent", 0.005);

        logger.info("OrderBookService initialized: enabled={}, depthLevels={}, CVD tracking=enabled", enabled,
                depthLevels);
    }

    /**
     * Initialize order book for a symbol
     */
    public void initializeOrderBook(String symbol) {
        if (!enabled) {
            return;
        }

        orderBooks.putIfAbsent(symbol, new OrderBook(symbol));
        logger.info("Initialized order book for {}", symbol);
    }

    /**
     * Process depth snapshot from WebSocket
     */
    public void processDepthSnapshot(String symbol, JSONObject depthData) {
        if (!enabled) {
            return;
        }

        OrderBook orderBook = orderBooks.get(symbol);
        if (orderBook == null) {
            initializeOrderBook(symbol);
            orderBook = orderBooks.get(symbol);
        }

        // Clear existing data
        orderBook.clear();

        // Process bids
        JSONArray bids = depthData.getJSONArray("bids");
        for (int i = 0; i < bids.length(); i++) {
            JSONArray bid = bids.getJSONArray(i);
            double price = bid.getDouble(0);
            double quantity = bid.getDouble(1);
            orderBook.updateBid(price, quantity);
        }

        // Process asks
        JSONArray asks = depthData.getJSONArray("asks");
        for (int i = 0; i < asks.length(); i++) {
            JSONArray ask = asks.getJSONArray(i);
            double price = ask.getDouble(0);
            double quantity = ask.getDouble(1);
            orderBook.updateAsk(price, quantity);
        }

        if (depthData.has("lastUpdateId")) {
            orderBook.setLastUpdateId(depthData.getLong("lastUpdateId"));
        }

        logger.debug("Processed depth snapshot for {}: {} bids, {} asks",
                symbol, orderBook.getBidLevels(), orderBook.getAskLevels());
    }

    /**
     * Process incremental depth update from WebSocket
     */
    public void processDepthUpdate(String symbol, JSONObject updateData) {
        if (!enabled) {
            return;
        }

        OrderBook orderBook = orderBooks.get(symbol);
        if (orderBook == null) {
            logger.warn("Received depth update for uninitialized symbol: {}", symbol);
            return;
        }

        // Update bids
        if (updateData.has("b")) {
            JSONArray bids = updateData.getJSONArray("b");
            for (int i = 0; i < bids.length(); i++) {
                JSONArray bid = bids.getJSONArray(i);
                double price = bid.getDouble(0);
                double quantity = bid.getDouble(1);
                orderBook.updateBid(price, quantity);
            }
        }

        // Update asks
        if (updateData.has("a")) {
            JSONArray asks = updateData.getJSONArray("a");
            for (int i = 0; i < asks.length(); i++) {
                JSONArray ask = asks.getJSONArray(i);
                double price = ask.getDouble(0);
                double quantity = ask.getDouble(1);
                orderBook.updateAsk(price, quantity);
            }
        }

        if (updateData.has("u")) {
            orderBook.setLastUpdateId(updateData.getLong("u"));
        }
    }

    /**
     * Get order book imbalance for a symbol
     * 
     * @return value between -1.0 (all asks) and +1.0 (all bids)
     */
    public double getImbalance(String symbol) {
        if (!enabled) {
            return 0.0;
        }

        OrderBook orderBook = orderBooks.get(symbol);
        if (orderBook == null) {
            return 0.0;
        }

        return orderBook.getImbalance(depthLevels);
    }

    /**
     * Get spread (best ask - best bid) as percentage of mid price
     */
    public double getSpreadPercent(String symbol) {
        if (!enabled) {
            return 0.0;
        }

        OrderBook orderBook = orderBooks.get(symbol);
        if (orderBook == null) {
            return 0.0;
        }

        Optional<Double> bestBid = orderBook.getBestBid();
        Optional<Double> bestAsk = orderBook.getBestAsk();

        if (bestBid.isEmpty() || bestAsk.isEmpty()) {
            return 0.0;
        }

        double midPrice = (bestBid.get() + bestAsk.get()) / 2.0;
        double spread = orderBook.getSpread();

        return spread / midPrice;
    }

    /**
     * Detect buy wall (support level)
     */
    public Optional<Double> detectBuyWall(String symbol) {
        if (!enabled) {
            return Optional.empty();
        }

        OrderBook orderBook = orderBooks.get(symbol);
        if (orderBook == null) {
            return Optional.empty();
        }

        return orderBook.detectBuyWall(wallStdDevMultiplier);
    }

    /**
     * Detect sell wall (resistance level)
     */
    public Optional<Double> detectSellWall(String symbol) {
        if (!enabled) {
            return Optional.empty();
        }

        OrderBook orderBook = orderBooks.get(symbol);
        if (orderBook == null) {
            return Optional.empty();
        }

        return orderBook.detectSellWall(wallStdDevMultiplier);
    }

    /**
     * Estimate slippage for a market order
     * 
     * @return slippage as percentage (e.g., 0.005 = 0.5%)
     */
    public double estimateSlippage(String symbol, String side, double quantityUSDT, double currentPrice) {
        if (!enabled) {
            return 0.0;
        }

        OrderBook orderBook = orderBooks.get(symbol);
        if (orderBook == null) {
            return 0.0;
        }

        return orderBook.estimateSlippage(side, quantityUSDT, currentPrice);
    }

    /**
     * Check if order book signal confirms BUY entry
     */
    public boolean confirmBuySignal(String symbol, double currentPrice) {
        if (!enabled) {
            return true; // If disabled, don't block trades
        }

        double imbalance = getImbalance(symbol);
        double spreadPercent = getSpreadPercent(symbol);
        Optional<Double> buyWall = detectBuyWall(symbol);

        // Check imbalance threshold
        if (imbalance < imbalanceThreshold) {
            logger.debug("{} Order book imbalance too low: {:.2f} (threshold: {:.2f})",
                    symbol, imbalance, imbalanceThreshold);
            return false;
        }

        // Check spread
        if (spreadPercent > maxSpreadPercent) {
            logger.debug("{} Spread too wide: {:.4f}% (max: {:.4f}%)",
                    symbol, spreadPercent * 100, maxSpreadPercent * 100);
            return false;
        }

        // Buy wall detection (optional boost or filter)
        if (wallFilterEnabled && buyWall.isPresent() && currentPrice > buyWall.get()) {
            logger.info("🟢 {} Buy Wall detected at {:.2f}, current price {:.2f} above wall - STRONG BUY",
                    symbol, buyWall.get(), currentPrice);
            return true;
        } else if (wallFilterEnabled && detectSellWall(symbol).isPresent()) {
            double sellWallPrice = detectSellWall(symbol).get();
            // If price is very close to a sell wall (resistance), we might want to avoid
            // buying
            double distToWall = (sellWallPrice - currentPrice) / currentPrice;
            if (distToWall < 0.002 && distToWall > 0) { // Within 0.2% of sell wall
                logger.info("⏸️ {} BUY filtered - Price {:.2f} too close to Sell Wall at {:.2f}",
                        symbol, currentPrice, sellWallPrice);
                return false;
            }
        }

        // All checks passed
        logger.debug("{} Order book confirms BUY: imbalance={:.2f}, spread={:.4f}%",
                symbol, imbalance, spreadPercent * 100);
        return true;
    }

    /**
     * Check if order book signal confirms SELL entry
     */
    public boolean confirmSellSignal(String symbol, double currentPrice) {
        if (!enabled) {
            return true;
        }

        double imbalance = getImbalance(symbol);
        double spreadPercent = getSpreadPercent(symbol);
        Optional<Double> sellWall = detectSellWall(symbol);

        // Check imbalance threshold (negative for sell)
        if (imbalance > -imbalanceThreshold) {
            logger.debug("{} Order book imbalance not bearish enough: {:.2f} (threshold: {:.2f})",
                    symbol, imbalance, -imbalanceThreshold);
            return false;
        }

        // Check spread
        if (spreadPercent > maxSpreadPercent) {
            logger.debug("{} Spread too wide: {:.4f}% (max: {:.4f}%)",
                    symbol, spreadPercent * 100, maxSpreadPercent * 100);
            return false;
        }

        // Sell wall detection
        if (wallFilterEnabled && sellWall.isPresent() && currentPrice < sellWall.get()) {
            logger.info("🔴 {} Sell Wall detected at {:.2f}, current price {:.2f} below wall - STRONG SELL",
                    symbol, sellWall.get(), currentPrice);
            return true;
        } else if (wallFilterEnabled && detectBuyWall(symbol).isPresent()) {
            double buyWallPrice = detectBuyWall(symbol).get();
            // If price is very close to a buy wall (support), we might want to avoid
            // selling
            double distToWall = (currentPrice - buyWallPrice) / currentPrice;
            if (distToWall < 0.002 && distToWall > 0) { // Within 0.2% of buy wall
                logger.info("⏸️ {} SELL filtered - Price {:.2f} too close to Buy Wall at {:.2f}",
                        symbol, currentPrice, buyWallPrice);
                return false;
            }
        }

        logger.debug("{} Order book confirms SELL: imbalance={:.2f}, spread={:.4f}%",
                symbol, imbalance, spreadPercent * 100);
        return true;
    }

    /**
     * Check if slippage is acceptable for order execution
     */
    public boolean isSlippageAcceptable(String symbol, String side, double quantityUSDT, double currentPrice) {
        if (!enabled) {
            return true;
        }

        double slippage = estimateSlippage(symbol, side, quantityUSDT, currentPrice);

        if (slippage > maxSlippagePercent) {
            logger.warn("⚠️ {} Slippage too high: {:.4f}% (max: {:.4f}%) - BLOCKING TRADE",
                    symbol, slippage * 100, maxSlippagePercent * 100);
            return false;
        }

        logger.debug("{} Slippage acceptable: {:.4f}%", symbol, slippage * 100);
        return true;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OrderBook getOrderBook(String symbol) {
        return orderBooks.get(symbol);
    }

    /**
     * Update CVD (Cumulative Volume Delta) based on order book changes
     * CVD = Cumulative(BuyVolume - SellVolume)
     * Positive CVD = Buying pressure, Negative CVD = Selling pressure
     */
    public void updateCVD(String symbol, double bidVolume, double askVolume) {
        double prevBid = previousBidVolume.getOrDefault(symbol, 0.0);
        double prevAsk = previousAskVolume.getOrDefault(symbol, 0.0);

        // Calculate delta (change in volume at bid vs ask)
        double bidDelta = bidVolume - prevBid;
        double askDelta = askVolume - prevAsk;

        // CVD: positive when buying absorbs selling, negative when selling absorbs
        // buying
        double delta = bidDelta - askDelta;

        // Accumulate
        double currentCVD = cumulativeVolumeDelta.getOrDefault(symbol, 0.0);
        cumulativeVolumeDelta.put(symbol, currentCVD + delta);

        // Store current values for next calculation
        previousBidVolume.put(symbol, bidVolume);
        previousAskVolume.put(symbol, askVolume);
    }

    /**
     * Get CVD value for a symbol
     * 
     * @return CVD value (positive = buy pressure, negative = sell pressure)
     */
    public double getCVD(String symbol) {
        return cumulativeVolumeDelta.getOrDefault(symbol, 0.0);
    }

    /**
     * Get CVD signal: confirms if order flow supports the trade direction
     * 
     * @param isLong true for LONG, false for SHORT
     * @return true if CVD confirms the direction
     */
    public boolean confirmCVDSignal(String symbol, boolean isLong) {
        double cvd = getCVD(symbol);

        if (isLong) {
            // For LONG: CVD should be positive (buying pressure)
            return cvd > 0;
        } else {
            // For SHORT: CVD should be negative (selling pressure)
            return cvd < 0;
        }
    }

    /**
     * Reset CVD for a symbol (call periodically or on significant events)
     */
    public void resetCVD(String symbol) {
        cumulativeVolumeDelta.put(symbol, 0.0);
        previousBidVolume.put(symbol, 0.0);
        previousAskVolume.put(symbol, 0.0);
    }
}
