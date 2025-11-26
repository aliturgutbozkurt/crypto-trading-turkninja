package com.turkninja.infra;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock Futures Binance Service for Backtesting
 * 
 * Simulates Binance Futures API responses without making real API calls.
 * Tracks virtual positions and balance for backtest simulation.
 */
public class MockFuturesBinanceService extends FuturesBinanceService {

    private static final Logger logger = LoggerFactory.getLogger(MockFuturesBinanceService.class);

    // Virtual backtest state
    private double virtualBalance = 1000.0; // Starting virtual balance
    private final Map<String, VirtualPosition> virtualPositions = new ConcurrentHashMap<>();
    private final Map<String, Double> currentPrices = new ConcurrentHashMap<>();
    private final List<OrderRecord> orderHistory = new ArrayList<>();
    private final List<com.turkninja.model.BacktestReport.TradeEntry> tradeHistory = new ArrayList<>(); // For backtest
    private long nextOrderId = 1L;

    /**
     * Virtual position data
     */
    private static class VirtualPosition {
        String symbol;
        String side; // "BUY" or "SELL"
        double quantity;
        double entryPrice;
        long entryTime;

        VirtualPosition(String symbol, String side, double quantity, double entryPrice) {
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
            this.entryPrice = entryPrice;
            this.entryTime = System.currentTimeMillis();
        }
    }

    /**
     * Order record for history
     */
    public static class OrderRecord {
        public long orderId;
        public String symbol;
        public String side;
        public double quantity;
        public double price;
        public long timestamp;

        public OrderRecord(long orderId, String symbol, String side, double quantity, double price) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.quantity = quantity;
            this.price = price;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final double feeRate;
    private final double slippage;

    public MockFuturesBinanceService(double initialBalance) {
        this(initialBalance, 0.0004, 0.0);
    }

    public MockFuturesBinanceService(double initialBalance, double feeRate, double slippage) {
        // Don't call super() - we don't need real API keys
        this.virtualBalance = initialBalance;
        this.feeRate = feeRate;
        this.slippage = slippage;
        logger.info("MockFuturesBinanceService initialized: Balance=${}, Fee={:.4f}, Slippage={:.4f}",
                initialBalance, feeRate, slippage);
    }

    /**
     * Set current price for a symbol (used by backtest engine)
     */
    public void setCurrentPrice(String symbol, double price) {
        currentPrices.put(symbol, price);
    }

    @Override
    public String getAccountInfo() {
        JSONObject account = new JSONObject();
        account.put("availableBalance", virtualBalance);
        account.put("totalWalletBalance", virtualBalance);

        JSONArray assets = new JSONArray();
        JSONObject usdt = new JSONObject();
        usdt.put("asset", "USDT");
        usdt.put("availableBalance", virtualBalance);
        assets.put(usdt);

        account.put("assets", assets);
        return account.toString();
    }

    @Override
    public double getAvailableBalance() {
        return virtualBalance;
    }

    @Override
    public String placeMarketOrder(String symbol, String side, double quantity) {
        try {
            Double currentPrice = currentPrices.get(symbol);
            if (currentPrice == null) {
                throw new RuntimeException("No price data for " + symbol);
            }

            // Simulate order execution at current price
            long orderId = nextOrderId++;

            // Calculate position value
            double notionalValue = quantity * currentPrice;
            double commission = notionalValue * feeRate;

            // Update virtual balance (deduct commission)
            virtualBalance -= commission;

            // Update/create position
            VirtualPosition position = virtualPositions.computeIfAbsent(symbol,
                    k -> new VirtualPosition(symbol, side, quantity, currentPrice));

            // Record order
            orderHistory.add(new OrderRecord(orderId, symbol, side, quantity, currentPrice));

            logger.debug("✅ MOCK Order: {} {} {} @ ${} (Commission: ${})",
                    side, quantity, symbol, currentPrice, String.format("%.4f", commission));

            // Return JSON response (simulated)
            JSONObject response = new JSONObject();
            response.put("orderId", orderId);
            response.put("symbol", symbol);
            response.put("status", "FILLED");
            response.put("side", side);
            response.put("type", "MARKET");
            response.put("executedQty", quantity);
            response.put("avgPrice", currentPrice);

            return response.toString();

        } catch (Exception e) {
            logger.error("Mock order failed: {}", e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Override
    public String closePosition(String symbol) {
        VirtualPosition position = virtualPositions.remove(symbol);

        if (position == null) {
            logger.warn("No position to close for {}", symbol);
            return "{\"info\": \"No open position\"}";
        }

        Double currentPrice = currentPrices.get(symbol);
        if (currentPrice == null) {
            logger.error("No price data for {} to close position", symbol);
            return "{\"error\": \"No price data\"}";
        }

        // Calculate P&L
        double pnl;
        if ("BUY".equals(position.side)) {
            pnl = (currentPrice - position.entryPrice) * position.quantity;
        } else { // SELL
            pnl = (position.entryPrice - currentPrice) * position.quantity;
        }

        // Apply exit commission
        double notionalValue = position.quantity * currentPrice;
        double exitCommission = notionalValue * feeRate;
        pnl -= exitCommission;

        // Update balance
        virtualBalance += pnl;

        logger.debug("✅ MOCK Close: {} {} - Entry: ${}, Exit: ${}, PnL: ${}",
                symbol, position.side, position.entryPrice, currentPrice, String.format("%.2f", pnl));

        // Record to trade history
        try {
            com.turkninja.model.BacktestReport.TradeEntry trade = new com.turkninja.model.BacktestReport.TradeEntry(
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(position.entryTime),
                            ZoneId.of("UTC")),
                    position.side,
                    position.entryPrice,
                    position.quantity);
            trade.exitTime = ZonedDateTime.now(ZoneId.of("UTC"));
            trade.exitPrice = currentPrice;
            trade.pnl = pnl;
            trade.pnlPercent = (pnl / (position.entryPrice * position.quantity)) * 100;
            trade.exitReason = "BACKTEST_CLOSE";
            trade.commission = exitCommission; // Store the actual exit commission

            tradeHistory.add(trade);
        } catch (Exception e) {
            logger.error("Error recording trade to history", e);
        }

        JSONObject response = new JSONObject();
        response.put("symbol", symbol);
        response.put("status", "CLOSED");
        response.put("pnl", pnl);
        response.put("exitPrice", currentPrice);

        return response.toString();
    }

    @Override
    public String getPositionInfo() {
        JSONArray positions = new JSONArray();

        for (VirtualPosition pos : virtualPositions.values()) {
            JSONObject posJson = new JSONObject();
            posJson.put("symbol", pos.symbol);
            posJson.put("positionSide", "BOTH"); // Simplified
            posJson.put("positionAmt", "BUY".equals(pos.side) ? pos.quantity : -pos.quantity);
            posJson.put("entryPrice", pos.entryPrice);
            posJson.put("unrealizedProfit", calculateUnrealizedPnL(pos));
            positions.put(posJson);
        }

        return positions.toString();
    }

    @Override
    public String getPositionInfo(String symbol) {
        JSONArray positions = new JSONArray();
        VirtualPosition pos = virtualPositions.get(symbol);

        if (pos != null) {
            JSONObject posJson = new JSONObject();
            posJson.put("symbol", pos.symbol);
            posJson.put("positionAmt", "BUY".equals(pos.side) ? pos.quantity : -pos.quantity);
            posJson.put("entryPrice", pos.entryPrice);
            posJson.put("unrealizedProfit", calculateUnrealizedPnL(pos));
            positions.put(posJson);
        }

        return positions.toString();
    }

    @Override
    public double getSymbolPriceTicker(String symbol) {
        return currentPrices.getOrDefault(symbol, 0.0);
    }

    /**
     * Calculate unrealized P&L for a position
     */
    private double calculateUnrealizedPnL(VirtualPosition pos) {
        Double currentPrice = currentPrices.get(pos.symbol);
        if (currentPrice == null)
            return 0.0;

        if ("BUY".equals(pos.side)) {
            return (currentPrice - pos.entryPrice) * pos.quantity;
        } else {
            return (pos.entryPrice - currentPrice) * pos.quantity;
        }
    }

    /**
     * Get current virtual balance
     */
    public double getVirtualBalance() {
        return virtualBalance;
    }

    /**
     * Get all virtual positions
     */
    public Map<String, VirtualPosition> getVirtualPositions() {
        return new HashMap<>(virtualPositions);
    }

    /**
     * Get order history
     */
    public List<OrderRecord> getOrderHistory() {
        return new ArrayList<>(orderHistory);
    }

    /**
     * Get trade history (completed trades for backtest report)
     */
    public List<com.turkninja.model.BacktestReport.TradeEntry> getTradeHistory() {
        return new ArrayList<>(tradeHistory);
    }

    /**
     * Reset backtest state
     */
    public void reset(double initialBalance) {
        virtualBalance = initialBalance;
        virtualPositions.clear();
        currentPrices.clear();
        orderHistory.clear();
        tradeHistory.clear();
        nextOrderId = 1L;
        logger.info("Mock service reset: Initial balance = ${:.2f}", initialBalance);
    }
}
