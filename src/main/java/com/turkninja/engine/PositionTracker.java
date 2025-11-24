package com.turkninja.engine;

import com.turkninja.infra.repository.TradeRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks open positions with entry price, stop-loss, and take-profit levels
 */
public class PositionTracker {
    private static final Logger logger = LoggerFactory.getLogger(PositionTracker.class);

    private final TradeRepository tradeRepository;
    private final RiskManager riskManager;
    private final Map<String, Position> activePositions = new ConcurrentHashMap<>();

    // Configuration for Active Trading (Fixed Dollar TP/SL)
    // User requirement: +$1 take profit, -$0.5 stop loss (1:2 risk/reward)
    private final double fixedTakeProfitDollar = 1.0; // $1 profit target
    private final double fixedStopLossDollar = 0.5; // $0.5 loss limit
    private final double minPositionUsdt = 5.0; // Ignore positions smaller than $5 (Dust)

    public PositionTracker(TradeRepository tradeRepository, RiskManager riskManager) {
        this.tradeRepository = tradeRepository;
        this.riskManager = riskManager;
        logger.info("Position Tracker initialized (TP: +${}, SL: -${})",
                fixedTakeProfitDollar, fixedStopLossDollar);
    }

    /**
     * Track a new position with fixed dollar TP/SL
     */
    public void trackPosition(String symbol, String side, double entryPrice, double quantity) {
        // Calculate TP/SL prices based on fixed dollar amounts
        // For LONG: TP when profit = +$1, SL when loss = -$1
        // For SHORT: TP when profit = +$1, SL when loss = -$1

        double profitPerUnit = fixedTakeProfitDollar / quantity;
        double lossPerUnit = fixedStopLossDollar / quantity;

        double stopLoss = side.equals("BUY")
                ? entryPrice - lossPerUnit
                : entryPrice + lossPerUnit;

        double takeProfit = side.equals("BUY")
                ? entryPrice + profitPerUnit
                : entryPrice - profitPerUnit;

        Position position = new Position(symbol, side, entryPrice, quantity, stopLoss, takeProfit);
        activePositions.put(symbol, position);

        // Register with RiskManager (use minimal trailing)
        riskManager.registerPosition(
                symbol,
                BigDecimal.valueOf(entryPrice),
                BigDecimal.valueOf(0.001)); // Minimal trailing (0.1%)

        // Persist to MongoDB
        savePositionToDb(position);

        logger.info("Tracking position: {} {} @ {} x{} (SL: ${}, TP: ${})",
                side, symbol, entryPrice, quantity,
                String.format("%.2f", entryPrice - stopLoss),
                String.format("%.2f", takeProfit - entryPrice));
    }

    /**
     * Check if position should be closed based on $1 stop-loss or take-profit
     */
    public PositionAction checkPosition(String symbol, double currentPrice) {
        Position position = activePositions.get(symbol);
        if (position == null)
            return PositionAction.HOLD;

        // Calculate current P&L in dollars
        double pnl;
        if (position.side.equals("BUY")) {
            pnl = (currentPrice - position.entryPrice) * position.quantity;
        } else {
            pnl = (position.entryPrice - currentPrice) * position.quantity;
        }

        // Stop-loss: Exit if loss >= $1
        if (pnl <= -fixedStopLossDollar) {
            logger.warn("⛔ Stop-loss triggered for {}: P&L=${} (threshold: -${})",
                    symbol, String.format("%.2f", pnl), fixedStopLossDollar);
            return PositionAction.CLOSE_STOP_LOSS;
        }

        // Take-profit: Exit if profit >= $1
        if (pnl >= fixedTakeProfitDollar) {
            logger.info("✅ Take-profit triggered for {}: P&L=${} (threshold: +${})",
                    symbol, String.format("%.2f", pnl), fixedTakeProfitDollar);
            return PositionAction.CLOSE_TAKE_PROFIT;
        }

        return PositionAction.HOLD;
    }

    /**
     * Remove position from tracking
     */
    public void removePosition(String symbol, double exitPrice) {
        Position position = activePositions.remove(symbol);
        if (position != null) {
            double pnl = 0.0;
            if (exitPrice > 0) {
                if (position.side.equals("BUY")) {
                    pnl = (exitPrice - position.entryPrice) * position.quantity;
                } else {
                    pnl = (position.entryPrice - exitPrice) * position.quantity;
                }
            }

            updatePositionInDb(position, "CLOSED", exitPrice, pnl);

            // Also clear from RiskManager
            riskManager.clearPosition(symbol);
            logger.info("Position removed from tracking: {} (Exit: {}, PnL: {})", symbol, exitPrice, pnl);
        }
    }

    /**
     * Sync positions with WebSocket update
     */
    public void syncPositions(org.json.JSONArray positions) {
        if (positions == null)
            return;

        for (int i = 0; i < positions.length(); i++) {
            org.json.JSONObject pos = positions.getJSONObject(i);
            String symbol = pos.getString("symbol");
            double amount = pos.getDouble("positionAmt");

            if (amount == 0) {
                // Position is closed, remove if we are tracking it
                if (activePositions.containsKey(symbol)) {
                    logger.info("Sync: Removing closed position for {}", symbol);
                    removePosition(symbol, 0.0);
                }
            } else {
                // Position is open
                if (!activePositions.containsKey(symbol)) {
                    // We found an external position! Track it.
                    double entryPrice = pos.getDouble("entryPrice");
                    double notionalValue = Math.abs(amount * entryPrice);

                    if (notionalValue < minPositionUsdt) {
                        // Ignore dust
                        return;
                    }

                    String side = amount > 0 ? "BUY" : "SELL";
                    logger.info("Sync: Found external position for {}. Tracking it.", symbol);
                    trackPosition(symbol, side, entryPrice, Math.abs(amount));
                }
            }
        }
    }

    /**
     * Get active position for a symbol
     */
    public Position getPosition(String symbol) {
        return activePositions.get(symbol);
    }

    /**
     * Check if there's an active position for a symbol
     */
    public boolean hasPosition(String symbol) {
        return activePositions.containsKey(symbol);
    }

    /**
     * Get all active positions
     */
    public Map<String, Position> getAllPositions() {
        return new ConcurrentHashMap<>(activePositions);
    }

    /**
     * Calculate unrealized P&L for a position
     */
    public double calculateUnrealizedPnL(String symbol, double currentPrice) {
        Position position = activePositions.get(symbol);
        if (position == null) {
            return 0.0;
        }

        double pnl;
        if (position.side.equals("BUY")) {
            pnl = (currentPrice - position.entryPrice) * position.quantity;
        } else {
            pnl = (position.entryPrice - currentPrice) * position.quantity;
        }

        return pnl;
    }

    /**
     * Save position to MongoDB
     */
    private void savePositionToDb(Position position) {
        try {
            Document doc = new Document()
                    .append("symbol", position.symbol)
                    .append("side", position.side)
                    .append("entryPrice", position.entryPrice)
                    .append("quantity", position.quantity)
                    .append("stopLoss", position.stopLoss)
                    .append("takeProfit", position.takeProfit)
                    .append("entryTime", position.entryTime)
                    .append("status", "OPEN");

            tradeRepository.saveTrade(doc);
        } catch (Exception e) {
            logger.error("Failed to save position to DB", e);
        }
    }

    /**
     * Update position status in MongoDB
     */
    private void updatePositionInDb(Position position, String status, double exitPrice, double pnl) {
        try {
            tradeRepository.updateTrade(position.symbol, exitPrice, pnl, status);
        } catch (Exception e) {
            logger.error("Failed to update position in DB", e);
        }
    }

    /**
     * Position data class
     */
    public static class Position {
        public final String symbol;
        public final String side;
        public final double entryPrice;
        public final double quantity;
        public final double stopLoss;
        public final double takeProfit;
        public final String entryTime;

        public Position(String symbol, String side, double entryPrice, double quantity,
                double stopLoss, double takeProfit) {
            this.symbol = symbol;
            this.side = side;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.entryTime = Instant.now().toString();
        }
    }

    /**
     * Position action enum
     */
    public enum PositionAction {
        HOLD,
        CLOSE_STOP_LOSS,
        CLOSE_TAKE_PROFIT
    }

    public TradeRepository getTradeRepository() {
        return tradeRepository;
    }
}
