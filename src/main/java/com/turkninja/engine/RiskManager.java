package com.turkninja.engine;

import com.turkninja.config.Config;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.infra.InfluxDBService;
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
    private FuturesWebSocketService webSocketService; // For cached mark prices
    private final OrderBookService orderBookService; // For liquidity and wall detection
    private final CorrelationService correlationService; // For correlation risk management
    private final InfluxDBService influxDBService; // Time-series data storage
    private final ScheduledExecutorService monitoringExecutor;
    private volatile boolean monitoringActive = false;

    // Risk limits
    private final double maxPositionSizeUsdt;
    private final int maxConcurrentPositions;
    private double dailyLossLimit;
    private double dailyLoss = 0.0;

    // Circuit Breaker for consecutive losses
    private int consecutiveLosses = 0;
    private final int MAX_CONSECUTIVE_LOSSES = 3;
    private volatile boolean tradingPaused = false;
    private long pauseUntilTime = 0;

    // Commission & Activation Settings
    private final double COMMISSION_RATE_ROUND_TRIP = 0.001; // 0.1% (0.05% Entry + 0.05% Exit)
    private final double TRAILING_ACTIVATION_THRESHOLD;

    // Order Book Aware Stop Settings
    private final boolean ORDER_BOOK_AWARE_ENABLED;
    private final double MAX_SLIPPAGE_PERCENT = 0.01; // 1% max slippage
    private final double EARLY_EXIT_SLIPPAGE_PERCENT = 0.008; // Exit early if slippage > 0.8%
    private final double WALL_PROXIMITY_PERCENT = 0.002; // 0.2% proximity to walls

    // Correlation Risk Settings
    private final boolean correlationFilterEnabled;
    private final double correlationThreshold;
    private final int minPositionsForCorrelation;

    public RiskManager(PositionTracker positionTracker, FuturesBinanceService futuresService,
            OrderBookService orderBookService, CorrelationService correlationService,
            InfluxDBService influxDBService) {
        this.positionTracker = positionTracker;
        this.futuresService = futuresService;
        this.orderBookService = orderBookService;
        this.correlationService = correlationService;
        this.influxDBService = influxDBService;
        this.maxConcurrentPositions = Config.getInt("risk.max_concurrent_positions", 1000);
        this.maxPositionSizeUsdt = Config.getDouble("risk.max_position_size", 1000.0);
        this.dailyLossLimit = Config.getDouble("risk.daily_loss_limit", 500.0);
        this.monitoringExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

        // Load trailing activation threshold from config (default 0.3%)
        this.TRAILING_ACTIVATION_THRESHOLD = Config.getDouble("strategy.trailing.activation.threshold", 0.003);

        // Load order book aware settings
        this.ORDER_BOOK_AWARE_ENABLED = Boolean.parseBoolean(Config.get("risk.orderbook.enabled", "true"));

        // Load correlation filter settings
        this.correlationFilterEnabled = Boolean.parseBoolean(Config.get("risk.correlation.enabled", "true"));
        this.correlationThreshold = Config.getDouble("risk.correlation.threshold", 0.75);
        this.minPositionsForCorrelation = Config.getInt("risk.correlation.min.positions", 3);

        logger.info(
                "RiskManager initialized with SL/TP automation (Max Position: ${}, Max Concurrent: {}, Daily Loss Limit: ${}, OrderBook Aware: {})",
                maxPositionSizeUsdt, maxConcurrentPositions, dailyLossLimit, ORDER_BOOK_AWARE_ENABLED);
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
                // Don't return - continue to check stop price even when making new highs
            }

            // If not activated yet, rely on fixed Stop Loss in PositionTracker
            if (!isActivated) {
                return false;
            }

            // Calculate Stop Price: High * (1 - trailingPct)
            BigDecimal stopPrice = extremePrice.multiply(BigDecimal.ONE.subtract(trailingPct));

            // Use smart stop check with order book intelligence
            double positionSizeUsdt = getPositionSizeUsdt(symbol);
            boolean shouldExit = shouldExitWithOrderBookCheck(
                    symbol, currentPrice, true, stopPrice, positionSizeUsdt);

            if (shouldExit) {
                BigDecimal grossProfit = extremePrice.subtract(entryPrice)
                        .divide(entryPrice, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                BigDecimal netProfit = grossProfit.subtract(BigDecimal.valueOf(COMMISSION_RATE_ROUND_TRIP * 100));

                logger.warn(
                        "🎯 Trailing Stop Triggered for {} (LONG)! Highest: {}, Net Profit Locked: {}%, Current: {}, Stop: {}",
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
                // Don't return - continue to check stop price even when making new lows
            }

            if (!isActivated) {
                // DEBUG LOG
                // logger.debug("Not activated for {}: Extreme={} > Activation={}", symbol,
                // extremePrice, activationPrice);
                return false;
            }

            // Calculate Stop Price: Low * (1 + trailingPct)
            BigDecimal stopPrice = extremePrice.multiply(BigDecimal.ONE.add(trailingPct));

            // DEBUG LOG
            if (symbol.equals("LINKUSDT") || symbol.equals("SOLUSDT") || symbol.equals("BCHUSDT")) {
                logger.info("🔍 CHECK {} SHORT: Current={}, Extreme={}, Stop={}, Activated={}",
                        symbol, currentPrice, extremePrice, stopPrice, isActivated);
            }

            // Use smart stop check with order book intelligence
            double positionSizeUsdt = getPositionSizeUsdt(symbol);
            boolean shouldExit = shouldExitWithOrderBookCheck(
                    symbol, currentPrice, false, stopPrice, positionSizeUsdt);

            if (shouldExit) {
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
     * Check if stop should be triggered with order book intelligence
     * Returns true if position should exit, considering:
     * 1. Normal stop/trailing stop logic
     * 2. Liquidity at stop level (slippage protection)
     * 3. Buy/sell walls (better execution)
     */
    private boolean shouldExitWithOrderBookCheck(String symbol, BigDecimal currentPrice,
            boolean isLong, BigDecimal stopPrice,
            double positionSizeUsdt) {
        // 1. Calculate normal stop condition
        boolean normalStopHit = isLong ? currentPrice.compareTo(stopPrice) <= 0
                : currentPrice.compareTo(stopPrice) >= 0;

        // 2. If order book disabled or unavailable, use normal logic only
        if (!ORDER_BOOK_AWARE_ENABLED || orderBookService == null || !orderBookService.isEnabled()) {
            return normalStopHit;
        }

        double currentPriceD = currentPrice.doubleValue();

        // 3. Estimate slippage at current price (exit execution)
        String exitSide = isLong ? "SELL" : "BUY";
        double estimatedSlippage = orderBookService.estimateSlippage(
                symbol, exitSide, positionSizeUsdt, currentPriceD);

        // 4. Check if slippage is dangerously high - EXIT EARLY!
        if (estimatedSlippage > EARLY_EXIT_SLIPPAGE_PERCENT) {
            logger.warn("⚠️ {} High slippage detected ({:.2f}%) - EXITING EARLY to protect from losses",
                    symbol, estimatedSlippage * 100);
            return true; // Exit now, before hitting actual stop
        }

        // 5. Check for walls that might prevent good execution
        if (!isLong) { // SHORT position - check buy walls
            java.util.Optional<Double> buyWall = orderBookService.detectBuyWall(symbol);
            if (buyWall.isPresent()) {
                double wallPrice = buyWall.get();

                // If approaching buy wall from below, exit before hitting it
                double distanceToWall = (wallPrice - currentPriceD) / currentPriceD;
                if (currentPriceD < wallPrice && distanceToWall < WALL_PROXIMITY_PERCENT) {
                    logger.info("📊 {} Buy wall at ${:.4f}, current ${:.4f} - exiting before wall",
                            symbol, wallPrice, currentPriceD);
                    return true;
                }
            }
        } else { // LONG position - check sell walls
            java.util.Optional<Double> sellWall = orderBookService.detectSellWall(symbol);
            if (sellWall.isPresent()) {
                double wallPrice = sellWall.get();

                // If approaching sell wall from above, exit before hitting it
                double distanceToWall = (currentPriceD - wallPrice) / currentPriceD;
                if (currentPriceD > wallPrice && distanceToWall < WALL_PROXIMITY_PERCENT) {
                    logger.info("📊 {} Sell wall at ${:.4f}, current ${:.4f} - exiting before wall",
                            symbol, wallPrice, currentPriceD);
                    return true;
                }
            }
        }

        // 6. Final check against normal stop price
        return normalStopHit;
    }

    /**
     * Helper method to get position size in USDT for slippage estimation
     */
    private double getPositionSizeUsdt(String symbol) {
        if (positionTracker == null) {
            return 100.0; // Default fallback
        }

        PositionTracker.Position position = positionTracker.getPosition(symbol);
        if (position == null) {
            return 100.0; // Default fallback
        }

        return Math.abs(position.quantity * position.entryPrice);
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
            // Calculate exit commission (0.04% of notional value)
            double quantity = Math.abs(position.quantity);
            double notionalValue = quantity * currentPrice;
            double exitCommission = notionalValue * 0.0004; // 0.04% exit fee

            logger.info("Closing position {} due to {}: Entry={}, Current={}, Exit Fee=${}",
                    symbol, reason, position.entryPrice, currentPrice, String.format("%.4f", exitCommission));

            // Close position via Futures API
            String result = futuresService.closePosition(symbol);
            logger.info("Position closed: {}", result);

            // Calculate realized P&L
            double pnl = positionTracker.calculateUnrealizedPnL(symbol, currentPrice);

            // Calculate position duration
            long durationSeconds = 0;
            if (position.entryTime != null) {
                durationSeconds = (System.currentTimeMillis()
                        - java.time.Instant.parse(position.entryTime).toEpochMilli()) / 1000;
            }

            // Record position close to InfluxDB
            if (influxDBService != null && influxDBService.isEnabled()) {
                influxDBService.writePositionClose(symbol, position.side, currentPrice, pnl, reason,
                        durationSeconds, java.time.Instant.now());
            }

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
     * Get current mark price for a symbol (uses WebSocket cache if available)
     */
    private double getCurrentMarkPrice(String symbol) {
        try {
            // Try WebSocket cache first (fast, real-time)
            if (webSocketService != null) {
                double cachedPrice = webSocketService.getMarkPrice(symbol);
                if (cachedPrice > 0) {
                    return cachedPrice;
                }
                // Only log warning occasionally to avoid spam
                if (Math.random() < 0.01) { // 1% of calls
                    logger.warn("Mark price cache MISS for {}, falling back to REST API", symbol);
                }
            }

            // Fallback to REST API if cache unavailable
            return futuresService.getSymbolPriceTicker(symbol);
        } catch (Exception e) {
            logger.error("Failed to get mark price for {}", symbol, e);
            return 0.0;
        }
    }

    /**
     * Set WebSocket service for cached mark price access
     */
    public void setWebSocketService(FuturesWebSocketService webSocketService) {
        this.webSocketService = webSocketService;
    }

    /**
     * Check trailing stops immediately when mark price updates (event-driven)
     * This provides faster response than the 1-second polling loop
     */
    public void checkPositionOnPriceUpdate(String symbol, double currentPrice) {
        onPriceUpdate(symbol, currentPrice);
    }

    /**
     * Process price update for a specific symbol (checks SL/TP and Trailing Stop)
     * Useful for backtesting and event-driven updates
     */
    public void onPriceUpdate(String symbol, double currentPrice) {
        try {
            PositionTracker.Position position = positionTracker.getPosition(symbol);
            if (position == null) {
                return; // No position for this symbol
            }

            // 1. Check Fixed SL/TP
            PositionTracker.PositionAction action = positionTracker.checkPosition(symbol, currentPrice);

            if (action == PositionTracker.PositionAction.CLOSE_STOP_LOSS) {
                closePosition(symbol, position, "STOP_LOSS", currentPrice);
                return;
            } else if (action == PositionTracker.PositionAction.CLOSE_TAKE_PROFIT) {
                closePosition(symbol, position, "TAKE_PROFIT", currentPrice);
                return;
            }

            // 2. Check Trailing Stop (only if fixed SL/TP didn't trigger)
            boolean trailingStopTriggered = checkExitCondition(
                    symbol,
                    BigDecimal.valueOf(currentPrice),
                    position.side.equals("BUY"));

            if (trailingStopTriggered) {
                logger.info("⚡ Real-time trailing stop triggered for {} at price {}", symbol, currentPrice);
                closePosition(symbol, position, "TRAILING_STOP", currentPrice);
            }
        } catch (Exception e) {
            logger.error("Error checking position on price update for {}", symbol, e);
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
     * Check correlation risk - prevent opening highly correlated positions
     * 
     * @param symbol Symbol to check
     * @param side   Position side (LONG/SHORT)
     * @return true if safe to open, false if too correlated
     */
    public boolean checkCorrelationRisk(String symbol, String side) {
        if (!correlationFilterEnabled) {
            return true; // Filter disabled
        }

        try {
            // Get open positions in same direction
            var openPositions = positionTracker.getAllPositions().values()
                    .stream()
                    .filter(p -> p.side.equalsIgnoreCase(side))
                    .toList();

            // If we have fewer than threshold positions, no correlation risk yet
            if (openPositions.size() < minPositionsForCorrelation) {
                logger.debug("✅ {} {} - Only {} positions, correlation check skipped",
                        symbol, side, openPositions.size());
                return true;
            }

            // Get symbols of open positions
            var openSymbols = openPositions.stream()
                    .map(p -> p.symbol)
                    .filter(s -> !s.equals(symbol)) // Exclude self
                    .toList();

            if (openSymbols.isEmpty()) {
                return true;
            }

            // Calculate average correlation
            double avgCorrelation = correlationService.getAverageCorrelation(symbol, openSymbols);

            // Check if too correlated
            if (avgCorrelation > correlationThreshold) {
                logger.warn(
                        "⚠️ {} {} REJECTED - High correlation ({:.2f}) with {} open {} positions (Threshold: {:.2f})",
                        symbol, side, avgCorrelation, openPositions.size(), side, correlationThreshold);

                return false; // REJECT
            }

            logger.info("✅ {} {} correlation check passed - Avg correlation: {:.2f} (Threshold: {:.2f})",
                    symbol, side, avgCorrelation, correlationThreshold);
            return true; // ACCEPT

        } catch (Exception e) {
            logger.error("Error checking correlation for {} {}", symbol, side, e);
            return true; // Don't block on errors
        }
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
