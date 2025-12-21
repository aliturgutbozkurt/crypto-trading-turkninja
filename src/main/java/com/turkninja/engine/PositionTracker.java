package com.turkninja.engine;

import com.turkninja.config.Config;
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

    private RiskManager riskManager;
    private KellyPositionSizer kellyPositionSizer; // Optional: for dynamic position sizing
    private final Map<String, Position> activePositions = new ConcurrentHashMap<>();

    public void setRiskManager(RiskManager riskManager) {
        this.riskManager = riskManager;
    }

    // Configuration for Active Trading (Percentage-based)
    private final double takeProfitPercent;
    private final double stopLossPercent;
    private final double trailingStopPercent;
    private final double minPositionUsdt = 5.0; // Ignore positions smaller than $5 (Dust)

    // Commission Rate (0.04% Entry + 0.04% Exit = 0.08%)
    private final double COMMISSION_RATE = 0.0008;

    public PositionTracker(RiskManager riskManager) {
        this.riskManager = riskManager;

        // Load TP/SL/Trailing from config
        this.takeProfitPercent = Config.getDouble("risk.take_profit_percent", 0.006); // Default 0.6%
        this.stopLossPercent = Config.getDouble("risk.stop_loss_percent", 0.003); // Default 0.3%
        this.trailingStopPercent = Config.getDouble("strategy.trailing.stop.percent", 0.002); // Default 0.2%

        logger.info("Position Tracker initialized (TP: {}%, SL: {}%, Trailing: {}%)",
                String.format("%.2f", takeProfitPercent * 100),
                String.format("%.2f", stopLossPercent * 100),
                String.format("%.2f", trailingStopPercent * 100));
    }

    /**
     * Default constructor for backtest mode (no RiskManager)
     */
    public PositionTracker() {
        this(null);
    }

    /**
     * Track a new position with default TP/SL (backward compatibility)
     */
    public void trackPosition(String symbol, String side, double entryPrice, double quantity) {
        trackPosition(symbol, side, entryPrice, quantity, this.stopLossPercent);
    }

    /**
     * Track a new position with custom stop loss percentage
     * 
     * @param stopLossPercentOverride Custom SL percentage (e.g., 0.02 for 2%)
     */
    public void trackPosition(String symbol, String side, double entryPrice, double quantity,
            double stopLossPercentOverride) {
        Position position = new Position(symbol, side, entryPrice, quantity, stopLossPercentOverride);
        activePositions.put(symbol, position);

        // Register with RiskManager for Trailing Stop
        riskManager.registerPosition(symbol, BigDecimal.valueOf(entryPrice), BigDecimal.valueOf(trailingStopPercent));

        logger.info("Tracking position: {} {} @ {} x{} (TP: +{}%, SL: -{}%, Dynamic: {})",
                side, symbol, entryPrice, quantity,
                String.format("%.2f", takeProfitPercent * 100),
                String.format("%.2f", stopLossPercentOverride * 100),
                stopLossPercentOverride != this.stopLossPercent ? "YES" : "NO");
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

        // Check Stop Loss (using position-specific SL)
        if (netPnlPercent <= -position.stopLossPercent) {
            logger.warn("⛔ Stop-loss triggered for {}: Net P&L={:.2f}% (Threshold: -{:.2f}%)",
                    symbol, netPnlPercent * 100, position.stopLossPercent * 100);
            return PositionAction.CLOSE_STOP_LOSS;
        }

        // FIXED TAKE PROFIT DISABLED - Using Trailing Stop instead (User preference)
        // Trailing Stop will protect profits dynamically
        /*
         * if (netPnlPercent >= takeProfitPercent) {
         * logger.
         * info("✅ Take-profit triggered for {}: Net P&L={:.2f}% (Threshold: +{:.2f}%)",
         * symbol, netPnlPercent * 100, takeProfitPercent * 100);
         * return PositionAction.CLOSE_TAKE_PROFIT;
         * }
         */

        return PositionAction.HOLD;
    }

    /**
     * Remove a position from tracking
     */
    public void removePosition(String symbol, double exitPrice) {
        Position removed = activePositions.remove(symbol);
        if (removed != null) {
            double pnl = calculateUnrealizedPnL(symbol, exitPrice);

            // Record trade to Kelly Position Sizer (for dynamic sizing)
            if (kellyPositionSizer != null && exitPrice > 0) {
                boolean isWin = pnl > 0;
                // Calculate profit ratio: (PnL / Position Size)
                double positionSize = removed.entryPrice * removed.quantity;
                double profitRatio = positionSize > 0 ? pnl / positionSize : 0.0;

                kellyPositionSizer.recordTrade(isWin, profitRatio);
                logger.debug("📊 Kelly: Recorded trade {} - Win: {}, Ratio: {:.4f}",
                        symbol, isWin, profitRatio);
            }

            // Also clear from RiskManager
            riskManager.clearPosition(symbol);
            logger.info("Position removed from tracking: {} (Exit: {}, PnL: {})", symbol, exitPrice, pnl);
        }
    }

    /**
     * Update position quantity after partial close
     * 
     * @param symbol      Symbol to update
     * @param newQuantity New quantity after partial close
     */
    public void updatePositionQuantity(String symbol, double newQuantity) {
        Position position = activePositions.get(symbol);
        if (position != null) {
            double oldQuantity = position.quantity;
            position.quantity = newQuantity;
            logger.info("📉 Position quantity updated for {}: {} → {} ({:.1f}% reduction)",
                    symbol, oldQuantity, newQuantity, ((oldQuantity - newQuantity) / oldQuantity) * 100);
        }
    }

    /**
     * Sync positions with WebSocket update
     * IMPORTANT: This preserves existing tracking state to avoid resetting trailing
     * stops
     */
    public void syncPositions(org.json.JSONArray positions) {
        if (positions == null)
            return;

        // Track which symbols we see from Binance
        java.util.Set<String> binanceSymbols = new java.util.HashSet<>();

        for (int i = 0; i < positions.length(); i++) {
            org.json.JSONObject pos = positions.getJSONObject(i);
            String symbol = pos.getString("symbol");
            double amount = pos.getDouble("positionAmt");

            if (amount != 0) {
                binanceSymbols.add(symbol);

                // Only add if NOT already tracking (this preserves trailing stop state)
                if (!activePositions.containsKey(symbol)) {
                    double entryPrice = pos.getDouble("entryPrice");
                    double notionalValue = Math.abs(amount * entryPrice);

                    if (notionalValue < minPositionUsdt) {
                        continue; // Skip dust
                    }

                    String side = amount > 0 ? "BUY" : "SELL";
                    logger.info("Sync: Found new external position for {}. Tracking it.", symbol);
                    trackPosition(symbol, side, entryPrice, Math.abs(amount));
                }
                // If already tracking, DO NOTHING (preserve trailing stop state)
            }
        }

        // Remove positions that are closed (not in Binance anymore)
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (String symbol : activePositions.keySet()) {
            if (!binanceSymbols.contains(symbol)) {
                toRemove.add(symbol);
            }
        }

        for (String symbol : toRemove) {
            logger.info("Sync: Removing closed position for {}", symbol);
            removePosition(symbol, 0.0);
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
     * Position data class
     */
    public static class Position {
        public final String symbol;
        public final String side;
        public final double entryPrice;
        public double quantity; // NOT final - can be updated for partial closes
        public final double stopLossPercent; // Custom SL for this position
        public final String entryTime;

        public Position(String symbol, String side, double entryPrice, double quantity) {
            this(symbol, side, entryPrice, quantity, 0.02); // Default 2% SL
        }

        public Position(String symbol, String side, double entryPrice, double quantity, double stopLossPercent) {
            this.symbol = symbol;
            this.side = side;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.stopLossPercent = stopLossPercent;
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

    /**
     * Set Kelly Position Sizer for dynamic position sizing based on trade history
     */
    public void setKellyPositionSizer(KellyPositionSizer kellyPositionSizer) {
        this.kellyPositionSizer = kellyPositionSizer;
        logger.info("Kelly Position Sizer connected to PositionTracker");
    }
}
