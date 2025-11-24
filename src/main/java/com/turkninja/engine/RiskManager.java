package com.turkninja.engine;

import com.turkninja.config.Config;
import com.turkninja.infra.FuturesBinanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RiskManager {
    private static final Logger logger = LoggerFactory.getLogger(RiskManager.class);

    // Symbol -> Highest Price since entry (for Long) or Lowest Price (for Short)
    private final Map<String, BigDecimal> extremePrices = new ConcurrentHashMap<>();

    // Symbol -> Trailing Stop Percentage (e.g., 0.02 for 2%)
    private final Map<String, BigDecimal> trailingStopPercentages = new ConcurrentHashMap<>();

    // Symbol -> Entry Price
    private final Map<String, BigDecimal> entryPrices = new ConcurrentHashMap<>();

    private PositionTracker positionTracker;
    private final FuturesBinanceService futuresService;
    private final ScheduledExecutorService monitoringExecutor;
    private volatile boolean monitoringActive = false;

    // Risk limits
    private final double maxPositionSizeUsdt = 100.0; // Max $100 per position
    private final int maxConcurrentPositions;
    private double dailyLossLimit = 50.0; // Max $50 daily loss
    private double dailyLoss = 0.0;

    // Circuit Breaker for consecutive losses
    private int consecutiveLosses = 0;
    private final int MAX_CONSECUTIVE_LOSSES = 3;
    private volatile boolean tradingPaused = false;
    private long pauseUntilTime = 0;

    public RiskManager(PositionTracker positionTracker, FuturesBinanceService futuresService) {
        this.positionTracker = positionTracker;
        this.futuresService = futuresService;
        this.maxConcurrentPositions = Config.getInt("risk.max_concurrent_positions", 7);
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        logger.info(
                "RiskManager initialized with SL/TP automation (Max Position: ${}, Max Concurrent: {}, Daily Loss Limit: ${})",
                maxPositionSizeUsdt, maxConcurrentPositions, dailyLossLimit);
    }

    public void setPositionTracker(PositionTracker positionTracker) {
        this.positionTracker = positionTracker;
    }

    public void registerPosition(String symbol, BigDecimal entryPrice, BigDecimal trailingStopPercentage) {
        entryPrices.put(symbol, entryPrice);
        extremePrices.put(symbol, entryPrice);
        trailingStopPercentages.put(symbol, trailingStopPercentage);
        logger.info("Registered position for {}: Entry={}, TrailingStop={}%", symbol, entryPrice,
                trailingStopPercentage.multiply(BigDecimal.valueOf(100)));
    }

    // Commission & Activation Settings
    private final double COMMISSION_RATE_ROUND_TRIP = 0.001; // 0.1% (0.05% Entry + 0.05% Exit)
    private final double TRAILING_ACTIVATION_THRESHOLD = 0.0005; // 0.05% - VERY FAST activation to lock profits

    public boolean checkExitCondition(String symbol, BigDecimal currentPrice, boolean isLong) {
        if (!entryPrices.containsKey(symbol))
            return false;

        BigDecimal entryPrice = entryPrices.get(symbol);
        BigDecimal extremePrice = extremePrices.get(symbol);
        BigDecimal trailingPct = trailingStopPercentages.get(symbol);

        if (isLong) {
            // Check for Activation (Price must exceed Entry + Threshold)
            BigDecimal activationPrice = entryPrice.multiply(BigDecimal.valueOf(1.0 + TRAILING_ACTIVATION_THRESHOLD));
            boolean isActivated = extremePrice.compareTo(activationPrice) >= 0;

            // Update highest price if current is higher
            if (currentPrice.compareTo(extremePrice) > 0) {
                // Only update extreme price if we are above activation OR if we just crossed it
                if (currentPrice.compareTo(activationPrice) >= 0) {
                    extremePrices.put(symbol, currentPrice);

                    BigDecimal grossProfitPct = currentPrice.subtract(entryPrice)
                            .divide(entryPrice, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    BigDecimal netProfitPct = grossProfitPct
                            .subtract(BigDecimal.valueOf(COMMISSION_RATE_ROUND_TRIP * 100));

                    logger.info("🔼 New High for {} LONG: {} (Entry: {}, Net PnL: {}%, Activated: YES)",
                            symbol, currentPrice, entryPrice, netProfitPct);
                }
                return false; // Still in trend
            }

            // If not activated yet, rely on fixed Stop Loss in PositionTracker
            if (!isActivated) {
                return false;
            }

            // Calculate Stop Price: High * (1 - trailingPct)
            BigDecimal stopPrice = extremePrice.multiply(BigDecimal.ONE.subtract(trailingPct));

            if (currentPrice.compareTo(stopPrice) <= 0) {
                BigDecimal grossProfit = extremePrice.subtract(entryPrice)
                        .divide(entryPrice, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                BigDecimal netProfit = grossProfit.subtract(BigDecimal.valueOf(COMMISSION_RATE_ROUND_TRIP * 100));

                logger.warn(
                        "🎯 Trailing Stop Triggered for {} (LONG)! Peak: {}, Net Profit Locked: {}%, Current: {}, Stop: {}",
                        symbol, extremePrice, netProfit, currentPrice, stopPrice);
                return true; // EXIT NOW
            }
        } else {
            // Short Position logic
            BigDecimal activationPrice = entryPrice.multiply(BigDecimal.valueOf(1.0 - TRAILING_ACTIVATION_THRESHOLD));
            boolean isActivated = extremePrice.compareTo(activationPrice) <= 0;

            // Update lowest price if current is lower
            if (currentPrice.compareTo(extremePrice) < 0) {
                if (currentPrice.compareTo(activationPrice) <= 0) {
                    extremePrices.put(symbol, currentPrice);

                    BigDecimal grossProfitPct = entryPrice.subtract(currentPrice)
                            .divide(entryPrice, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    BigDecimal netProfitPct = grossProfitPct
                            .subtract(BigDecimal.valueOf(COMMISSION_RATE_ROUND_TRIP * 100));

                    logger.info("🔽 New Low for {} SHORT: {} (Entry: {}, Net PnL: {}%, Activated: YES)",
                            symbol, currentPrice, entryPrice, netProfitPct);
                }
                return false;
            }

            if (!isActivated) {
                return false;
            }

            // Calculate Stop Price: Low * (1 + trailingPct)
            BigDecimal stopPrice = extremePrice.multiply(BigDecimal.ONE.add(trailingPct));

            if (currentPrice.compareTo(stopPrice) >= 0) {
                BigDecimal grossProfit = entryPrice.subtract(extremePrice)
                        .divide(entryPrice, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                BigDecimal netProfit = grossProfit.subtract(BigDecimal.valueOf(COMMISSION_RATE_ROUND_TRIP * 100));

                logger.warn(
                        "🎯 Trailing Stop Triggered for {} (SHORT)! Lowest: {}, Net Profit Locked: {}%, Current: {}, Stop: {}",
                        symbol, extremePrice, netProfit, currentPrice, stopPrice);
                return true; // EXIT NOW
            }
        }

        return false;
    }

    public void clearPosition(String symbol) {
        entryPrices.remove(symbol);
        extremePrices.remove(symbol);
        trailingStopPercentages.remove(symbol);
    }

    /**
     * Start automated position monitoring for stop-loss/take-profit
     */
    public void startMonitoring() {
        if (monitoringActive) {
            logger.warn("Monitoring already active");
            return;
        }

        monitoringActive = true;
        monitoringExecutor.scheduleAtFixedRate(this::monitorPositions, 0, 1, TimeUnit.SECONDS);
        logger.info("Position monitoring started (checking every 1 second)");
    }

    /**
     * Stop automated position monitoring
     */
    public void stopMonitoring() {
        monitoringActive = false;
        logger.info("Position monitoring stopped");
    }

    /**
     * Monitor all positions and close if stop-loss or take-profit is triggered
     */
    private void monitorPositions() {
        if (!monitoringActive)
            return;

        try {
            Map<String, PositionTracker.Position> positions = positionTracker.getAllPositions();

            for (Map.Entry<String, PositionTracker.Position> entry : positions.entrySet()) {
                String symbol = entry.getKey();
                PositionTracker.Position position = entry.getValue();

                // Get current mark price (more reliable than last price)
                double currentPrice = getCurrentMarkPrice(symbol);
                if (currentPrice <= 0)
                    continue;

                // Check if position should be closed (Fixed SL/TP)
                PositionTracker.PositionAction action = positionTracker.checkPosition(symbol, currentPrice);

                if (action == PositionTracker.PositionAction.CLOSE_STOP_LOSS) {
                    closePosition(symbol, position, "STOP_LOSS", currentPrice);
                } else if (action == PositionTracker.PositionAction.CLOSE_TAKE_PROFIT) {
                    closePosition(symbol, position, "TAKE_PROFIT", currentPrice);
                } else {
                    // Check Trailing Stop (only if fixed SL/TP didn't trigger)
                    boolean trailingStopTriggered = checkExitCondition(symbol, BigDecimal.valueOf(currentPrice),
                            position.side.equals("BUY"));
                    if (trailingStopTriggered) {
                        closePosition(symbol, position, "TRAILING_STOP", currentPrice);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error monitoring positions", e);
        }
    }

    /**
     * Close a position
     */
    private void closePosition(String symbol, PositionTracker.Position position, String reason, double currentPrice) {
        try {
            logger.info("Closing position {} due to {}: Entry={}, Current={}",
                    symbol, reason, position.entryPrice, currentPrice);

            // Close position via Futures API
            String result = futuresService.closePosition(symbol);
            logger.info("Position closed: {}", result);

            // Calculate realized P&L
            double pnl = positionTracker.calculateUnrealizedPnL(symbol, currentPrice);
            if (pnl < 0) {
                dailyLoss += Math.abs(pnl);
                logger.warn("Daily loss updated: ${} / ${}", dailyLoss, dailyLossLimit);

                // Check if daily loss limit is hit
                if (dailyLoss >= dailyLossLimit) {
                    logger.error("DAILY LOSS LIMIT HIT! Stopping all trading.");
                    stopMonitoring();
                    emergencyExit();
                }
            }

            // Remove from tracking
            positionTracker.removePosition(symbol, currentPrice);
            clearPosition(symbol);

        } catch (Exception e) {
            logger.error("Failed to close position for {}", symbol, e);
        }
    }

    /**
     * Get current mark price for a symbol
     */
    private double getCurrentMarkPrice(String symbol) {
        try {
            return futuresService.getSymbolPriceTicker(symbol);
        } catch (Exception e) {
            logger.error("Failed to get mark price for {}", symbol, e);
            return 0.0;
        }
    }

    /**
     * Emergency exit - close all positions immediately
     */
    public void emergencyExit() {
        logger.warn("EMERGENCY EXIT TRIGGERED - Closing all positions");

        Map<String, PositionTracker.Position> positions = positionTracker.getAllPositions();
        for (String symbol : positions.keySet()) {
            try {
                String result = futuresService.closePosition(symbol);
                logger.info("Emergency closed {}: {}", symbol, result);
                double exitPrice = getCurrentMarkPrice(symbol);
                positionTracker.removePosition(symbol, exitPrice);
                clearPosition(symbol);
            } catch (Exception e) {
                logger.error("Failed to emergency close {}", symbol, e);
            }
        }

        stopMonitoring();
    }

    /**
     * Check if new position is allowed based on risk limits
     */
    public boolean canOpenPosition(double positionSizeUsdt) {
        // 0. Circuit Breaker - Check if trading is paused
        if (tradingPaused) {
            long currentTime = System.currentTimeMillis();
            if (currentTime < pauseUntilTime) {
                long remainingMinutes = (pauseUntilTime - currentTime) / 60000;
                logger.warn("⛔ Trading PAUSED due to {} consecutive losses. Resuming in {} minutes",
                        MAX_CONSECUTIVE_LOSSES, remainingMinutes);
                return false;
            } else {
                // Resume trading
                tradingPaused = false;
                consecutiveLosses = 0;
                logger.info("✅ Trading RESUMED after pause period");
            }
        }

        // 1. Check position count limit
        int currentPositions = positionTracker.getAllPositions().size();
        if (currentPositions >= maxConcurrentPositions) {
            logger.warn("Cannot open position: Max concurrent positions reached ({}/{})",
                    currentPositions, maxConcurrentPositions);
            return false;
        }

        // 2. Check position size limit
        if (positionSizeUsdt > maxPositionSizeUsdt) {
            logger.warn("Cannot open position: Position size ${} exceeds max ${}",
                    positionSizeUsdt, maxPositionSizeUsdt);
            return false;
        }

        // 3. Check daily loss limit
        if (dailyLoss >= dailyLossLimit) {
            logger.warn("Daily loss limit reached: ${} / ${}", dailyLoss, dailyLossLimit);
            return false;
        }

        return true;
    }

    /**
     * Records a trade's PnL and updates risk metrics.
     * Triggers circuit breaker if consecutive losses exceed limit.
     * 
     * @param pnl The PnL of the closed trade.
     */
    public void recordTrade(double pnl) {
        if (pnl < 0) {
            dailyLoss += Math.abs(pnl);
            consecutiveLosses++;
            logger.warn("❌ Trade closed with loss: ${} (Daily loss: ${}, Consecutive: {})",
                    pnl, dailyLoss, consecutiveLosses);

            // Check if daily loss limit is hit
            if (dailyLoss >= dailyLossLimit) {
                logger.error("DAILY LOSS LIMIT HIT! Stopping all trading.");
                stopMonitoring();
                emergencyExit();
            }

            // Trigger circuit breaker
            if (consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) {
                tradingPaused = true;
                pauseUntilTime = System.currentTimeMillis() + (6 * 60 * 60 * 1000); // 6 hours
                logger.error("🛑 CIRCUIT BREAKER TRIGGERED! Trading paused for 6 hours after {} consecutive losses",
                        consecutiveLosses);
            }
        } else {
            // Reset on win
            consecutiveLosses = 0;
            logger.info("✅ Trade closed with profit: ${} (Consecutive losses reset)", pnl);
        }
    }

    /**
     * Reset daily loss counter (call at start of each day)
     */
    public void resetDailyLoss() {
        dailyLoss = 0.0;
        consecutiveLosses = 0;
        tradingPaused = false;
        logger.info("Daily loss reset to $0.00, circuit breaker reset");
    }

    /**
     * Shutdown monitoring executor
     */
    public void shutdown() {
        stopMonitoring();
        monitoringExecutor.shutdown();
        logger.info("RiskManager shutdown");
    }
}
