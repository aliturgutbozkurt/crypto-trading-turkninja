package com.turkninja.engine;

import com.turkninja.config.Config;
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

    // Configuration for Active Trading (Percentage-based)
    private final double takeProfitPercent;
    private final double stopLossPercent;
    private final double trailingStopPercent;
    private final double minPositionUsdt = 5.0; // Ignore positions smaller than $5 (Dust)

    // Commission Rate (0.04% Entry + 0.04% Exit = 0.08%)
    private final double COMMISSION_RATE = 0.0008;

    public PositionTracker(TradeRepository tradeRepository, RiskManager riskManager) {
        this.tradeRepository = tradeRepository;
        this.riskManager = riskManager;

        // Load TP/SL/Trailing from config
        this.takeProfitPercent = Config.getDouble("strategy.tp.percent", 0.006); // Default 0.6%
        this.stopLossPercent = Config.getDouble("strategy.sl.percent", 0.003); // Default 0.3%
        this.trailingStopPercent = Config.getDouble("strategy.trailing.stop.percent", 0.002); // Default 0.2%

        logger.info("Position Tracker initialized (TP: {}%, SL: {}%, Trailing: {}%)",
                String.format("%.2f", takeProfitPercent * 100),
                String.format("%.2f", stopLossPercent * 100),
                String.format("%.2f", trailingStopPercent * 100));
    }

    /**
     * Track a new position with percentage-based TP/SL
     */
    public void trackPosition(String symbol, String side, double entryPrice, double quantity) {
        Position position = new Position(symbol, side, entryPrice, quantity);
        activePositions.put(symbol, position);

        // Register with RiskManager for Trailing Stop
        riskManager.registerPosition(symbol, BigDecimal.valueOf(entryPrice), BigDecimal.valueOf(trailingStopPercent));

        // Persist to MongoDB
        savePositionToDb(position);

        logger.info("Tracking position: {} {} @ {} x{} (TP: +{}%, SL: -{}%)",
                side, symbol, entryPrice, quantity,
                String.format("%.2f", takeProfitPercent * 100),
                String.format("%.2f", stopLossPercent * 100));
    }

    /**
     * Check if a position should be closed based on TP/SL
     */
    public PositionAction checkPosition(String symbol, double currentPrice) {
        Position position = activePositions.get(symbol);
        if (position == null) {
            return PositionAction.HOLD;
        }

        // Calculate P&L Percentage
        double entryPrice = position.entryPrice;
        double priceDiff = (currentPrice - entryPrice);

        // Adjust for SHORT positions
        if (position.side.equals("SELL")) {
            priceDiff = (entryPrice - currentPrice);
        }

        double grossPnlPercent = priceDiff / entryPrice;
        double netPnlPercent = grossPnlPercent - COMMISSION_RATE;

        // Check Stop Loss
        if (netPnlPercent <= -stopLossPercent) {
            logger.warn("⛔ Stop-loss triggered for {}: Net P&L={:.2f}% (Threshold: -{:.2f}%)",
                    symbol, netPnlPercent * 100, stopLossPercent * 100);
            return PositionAction.CLOSE_STOP_LOSS;
        }

        // Check Take Profit
        if (netPnlPercent >= takeProfitPercent) {
            logger.info("✅ Take-profit triggered for {}: Net P&L={:.2f}% (Threshold: +{:.2f}%)",
                    symbol, netPnlPercent * 100, takeProfitPercent * 100);
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

        // Calculate gross P&L
        double grossPnl;
        if (position.side.equals("BUY")) {
            grossPnl = (currentPrice - position.entryPrice) * position.quantity;
        } else {
            grossPnl = (position.entryPrice - currentPrice) * position.quantity;
        }

        // Deduct commission (0.08% total: entry 0.04% + exit 0.04%)
        double entryNotional = position.entryPrice * position.quantity;
        double exitNotional = currentPrice * position.quantity;
        double entryCommission = entryNotional * 0.0004;
        double exitCommission = exitNotional * 0.0004;
        double totalCommission = entryCommission + exitCommission;

        // Return NET P&L (after commission)
        return grossPnl - totalCommission;
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
        public final String entryTime;

        public Position(String symbol, String side, double entryPrice, double quantity) {
            this.symbol = symbol;
            this.side = side;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
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
