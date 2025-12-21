package com.turkninja.web.controller;

import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.infra.InfluxDBService;
import com.turkninja.engine.PositionTracker;
import com.turkninja.engine.RiskManager;
import com.turkninja.engine.StrategyEngine;
import com.turkninja.web.dto.PositionDTO;
import com.turkninja.web.dto.AccountDTO;
import com.turkninja.web.dto.TradeHistoryDTO;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DashboardRestController {
    private static final Logger logger = LoggerFactory.getLogger(DashboardRestController.class);

    private final FuturesBinanceService futuresService;
    private final FuturesWebSocketService webSocketService;
    private final PositionTracker positionTracker;
    private final RiskManager riskManager;
    private final StrategyEngine strategyEngine;
    private final InfluxDBService influxDBService;

    public DashboardRestController(FuturesBinanceService futuresService,
            FuturesWebSocketService webSocketService,
            PositionTracker positionTracker,
            RiskManager riskManager,
            StrategyEngine strategyEngine,
            InfluxDBService influxDBService) {
        this.futuresService = futuresService;
        this.webSocketService = webSocketService;
        this.positionTracker = positionTracker;
        this.riskManager = riskManager;
        this.strategyEngine = strategyEngine;
        this.influxDBService = influxDBService;
    }

    @GetMapping("/positions")
    public List<PositionDTO> getPositions() {
        List<PositionDTO> positions = new ArrayList<>();
        try {
            JSONArray positionsArray = webSocketService.getCachedPositions();
            JSONObject account = webSocketService.getCachedAccountInfo();
            double balance = 0.0;

            // 1. Try getting balance from cache (handle both keys)
            if (account != null) {
                balance = account.optDouble("totalMarginBalance", account.optDouble("totalWalletBalance", 0.0));
            }

            // 2. Fallback to REST API if cache is empty or zero
            if (balance == 0) {
                try {
                    String accountJson = futuresService.getAccountInfo();
                    JSONObject accountRest = new JSONObject(accountJson);
                    balance = accountRest.optDouble("totalMarginBalance",
                            accountRest.optDouble("totalWalletBalance", 0.0));
                } catch (Exception e) {
                    // Ignore fallback failure
                }
            }

            if (positionsArray != null) {
                for (int i = 0; i < positionsArray.length(); i++) {
                    JSONObject pos = positionsArray.getJSONObject(i);
                    double positionAmt = pos.getDouble("positionAmt");

                    if (positionAmt != 0) {
                        String symbol = pos.getString("symbol");
                        String side = positionAmt > 0 ? "LONG" : "SHORT";
                        double entryPrice = pos.getDouble("entryPrice");

                        // Get mark price from cache (WebSocket updates don't include it in position
                        // object)
                        double markPrice = webSocketService.getMarkPrice(symbol);
                        if (markPrice == 0) {
                            // Fallback if not in cache yet (try to get from position object if available,
                            // e.g. from REST)
                            markPrice = pos.optDouble("markPrice", entryPrice);
                        }

                        // Calculate PnL dynamically using real-time mark price
                        // Formula: (markPrice - entryPrice) * positionAmt
                        // This works for both LONG (posAmt > 0) and SHORT (posAmt < 0)
                        double unrealizedProfit;
                        if (markPrice != 0) {
                            unrealizedProfit = (markPrice - entryPrice) * positionAmt;
                        } else {
                            // Fallback to cached PnL if mark price is missing
                            unrealizedProfit = pos.optDouble("unRealizedProfit",
                                    pos.optDouble("unrealizedProfit", 0.0));
                        }

                        double notional = Math.abs(positionAmt * markPrice);
                        // ROI = (PnL / Initial Margin) * 100
                        // Initial Margin = Notional / Leverage (assuming 20x as per strategy)
                        double leverage = 20.0;
                        double initialMargin = notional / leverage;
                        double roiPercent = initialMargin != 0 ? (unrealizedProfit / initialMargin) * 100 : 0;

                        double balancePercent = balance > 0 ? (initialMargin / balance) * 100 : 0; // Balance % is based
                                                                                                   // on Margin used

                        // Get entry time (priority: InfluxDB > PositionTracker > Binance updateTime >
                        // current time)
                        long entryTimeMs = System.currentTimeMillis(); // Default fallback
                        long currentTimeMs = System.currentTimeMillis();

                        // 1. Try InfluxDB first (survives restarts)
                        if (influxDBService != null && influxDBService.isEnabled()) {
                            Long influxEntryTime = influxDBService.getPositionEntryTime(symbol);
                            if (influxEntryTime != null && influxEntryTime > 0 && influxEntryTime < currentTimeMs) {
                                entryTimeMs = influxEntryTime;
                            }
                        }

                        // 2. Try PositionTracker if InfluxDB didn't have it
                        if (entryTimeMs == currentTimeMs) {
                            PositionTracker.Position trackedPosition = positionTracker.getPosition(symbol);
                            if (trackedPosition != null && trackedPosition.entryTime != null) {
                                try {
                                    long trackedTime = java.time.Instant.parse(trackedPosition.entryTime)
                                            .toEpochMilli();
                                    if (trackedTime > 0 && trackedTime < currentTimeMs) {
                                        entryTimeMs = trackedTime;
                                    }
                                } catch (Exception e) {
                                    // Keep trying other sources
                                }
                            }
                        }

                        // 3. Try Binance updateTime as last resort
                        if (entryTimeMs == currentTimeMs) {
                            long binanceUpdateTime = pos.optLong("updateTime", 0);
                            if (binanceUpdateTime > 0 && binanceUpdateTime < currentTimeMs) {
                                entryTimeMs = binanceUpdateTime;
                            }
                        }

                        // Final validation: if entry time is still suspicious (future or >24h ago for
                        // new position)
                        // For positions without tracked entry time, assume they were opened recently
                        if (entryTimeMs >= currentTimeMs || entryTimeMs < (currentTimeMs - 86400000)) {
                            // If no valid entry time found, use current time (will show "0h 0m 0s" until
                            // properly tracked)
                            entryTimeMs = currentTimeMs;
                        }

                        positions.add(new PositionDTO(symbol, side, entryPrice, markPrice,
                                unrealizedProfit, roiPercent, notional, balancePercent, entryTimeMs));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return positions;
    }

    @GetMapping("/account")
    public AccountDTO getAccount() {
        try {
            // Check if paper trading mode is enabled
            boolean isDryRun = Boolean
                    .parseBoolean(com.turkninja.config.Config.get(com.turkninja.config.Config.DRY_RUN, "false"));
            double paperTradingBalance = com.turkninja.config.Config.getDouble("paper.trading.initial_balance", 100.0);

            if (isDryRun) {
                // Paper Trading Mode - return virtual balance
                String strategyStatus = strategyEngine != null && strategyEngine.isTradingActive() ? "Active (Paper)"
                        : "Stopped";
                logger.info("📝 Paper Trading Mode: Virtual balance = ${}", paperTradingBalance);
                return new AccountDTO(paperTradingBalance, 0.0, 0.0, 0, strategyStatus);
            }

            JSONObject account = webSocketService.getCachedAccountInfo();

            if (account != null) {
                // Get base wallet balance (this updates less frequently, which is fine)
                double totalWalletBalance = account.optDouble("totalWalletBalance", 0.0);

                // Calculate LIVE Unrealized PnL from all active positions using real-time mark
                // prices
                double liveUnrealizedPnL = 0.0;
                JSONArray positionsArray = webSocketService.getCachedPositions();

                if (positionsArray != null) {
                    for (int i = 0; i < positionsArray.length(); i++) {
                        JSONObject pos = positionsArray.getJSONObject(i);
                        double positionAmt = pos.getDouble("positionAmt");

                        if (positionAmt != 0) {
                            String symbol = pos.getString("symbol");
                            double entryPrice = pos.getDouble("entryPrice");

                            // Get real-time mark price
                            double markPrice = webSocketService.getMarkPrice(symbol);
                            if (markPrice == 0) {
                                markPrice = pos.optDouble("markPrice", entryPrice);
                            }

                            if (markPrice != 0) {
                                liveUnrealizedPnL += (markPrice - entryPrice) * positionAmt;
                            } else {
                                // Fallback to cached PnL
                                liveUnrealizedPnL += pos.optDouble("unRealizedProfit",
                                        pos.optDouble("unrealizedProfit", 0.0));
                            }
                        }
                    }
                }

                // Calculate Total Margin Balance = Wallet Balance + Unrealized PnL
                double totalMarginBalance = totalWalletBalance + liveUnrealizedPnL;

                // Count ACTUAL active positions from Binance (not PositionTracker)
                int activePositions = 0;
                if (positionsArray != null) {
                    for (int i = 0; i < positionsArray.length(); i++) {
                        JSONObject pos = positionsArray.getJSONObject(i);
                        double positionAmt = pos.getDouble("positionAmt");
                        if (positionAmt != 0) {
                            activePositions++;
                        }
                    }
                }

                String strategyStatus = strategyEngine != null && strategyEngine.isTradingActive() ? "Active"
                        : "Stopped";

                return new AccountDTO(totalMarginBalance, liveUnrealizedPnL, 0.0, activePositions, strategyStatus);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return new AccountDTO(0, 0, 0, 0, "Unknown");
    }

    @PostMapping("/emergency-exit")
    public String emergencyExit() {
        try {
            if (riskManager != null) {
                riskManager.emergencyExit();
                return "Emergency exit executed";
            }
            return "RiskManager not available";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @PostMapping("/close-position")
    public String closePosition(@RequestParam("symbol") String symbol) {
        try {
            if (futuresService != null) {
                // Close position on Binance
                String result = futuresService.closePosition(symbol);

                // Remove from local tracker immediately for UI responsiveness
                if (positionTracker != null) {
                    positionTracker.removePosition(symbol, 0.0);
                }

                return result;
            }
            return "FuturesService not available";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @PostMapping("/reload-positions")
    public String reloadPositions() {
        try {
            String positionsJson = futuresService.getPositionInfo();
            JSONArray positionsArr = new JSONArray(positionsJson);

            // Update WebSocket cache
            webSocketService.setCachedPositions(positionsArr);

            // Sync PositionTracker (smart sync preserves existing tracking state)
            if (positionTracker != null) {
                positionTracker.syncPositions(positionsArr);
            }

            return "OK";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @GetMapping("/signals")
    public List<com.turkninja.web.dto.SignalDTO> getSignals() {
        if (strategyEngine != null) {
            return strategyEngine.getRecentSignals();
        }
        return new ArrayList<>();
    }

    /**
     * Get trade history with pagination
     * Combines active positions and closed trades from InfluxDB
     */
    @GetMapping("/trade-history")
    public java.util.Map<String, Object> getTradeHistory(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        try {
            // Get closed trades from InfluxDB
            List<TradeHistoryDTO> closedTrades = influxDBService != null
                    ? influxDBService.queryRecentTrades(limit, offset)
                    : new ArrayList<>();

            // Get active positions from PositionTracker and convert to TradeHistoryDTO
            List<TradeHistoryDTO> activeTrades = new ArrayList<>();
            if (positionTracker != null) {
                java.util.Map<String, PositionTracker.Position> activePositions = positionTracker.getAllPositions();

                for (PositionTracker.Position pos : activePositions.values()) {
                    // Get current mark price for P&L calculation
                    double currentPrice = webSocketService != null
                            ? webSocketService.getMarkPrice(pos.symbol)
                            : 0.0;

                    if (currentPrice > 0) {
                        double pnl = positionTracker.calculateUnrealizedPnL(pos.symbol, currentPrice);
                        double pnlPercent = ((currentPrice - pos.entryPrice) / pos.entryPrice) * 100;
                        if (pos.side.equals("SELL")) {
                            pnlPercent = -pnlPercent;
                        }

                        TradeHistoryDTO activeTrade = new TradeHistoryDTO(
                                pos.symbol, pos.side, pos.entryPrice, null,
                                pnl, pnlPercent, null, null,
                                java.time.Instant.parse(pos.entryTime), "ACTIVE");

                        activeTrades.add(activeTrade);
                    }
                }
            }

            long totalClosed = influxDBService != null ? influxDBService.getTotalTradeCount() : 0;

            // Get aggregate metrics
            java.util.Map<String, Object> metrics = influxDBService != null
                    ? influxDBService.getAggregateMetrics()
                    : java.util.Map.of(
                            "totalPnL", 0.0,
                            "winRate", 0.0,
                            "totalTrades", 0L,
                            "winningTrades", 0L);

            return java.util.Map.of(
                    "active", activeTrades,
                    "closed", closedTrades,
                    "totalClosed", totalClosed,
                    "stats", metrics,
                    "limit", limit,
                    "offset", offset);

        } catch (Exception e) {
            logger.error("Error fetching trade history", e);
            return java.util.Map.of(
                    "active", new ArrayList<>(),
                    "closed", new ArrayList<>(),
                    "totalClosed", 0L,
                    "stats", java.util.Map.of(
                            "totalPnL", 0.0,
                            "winRate", 0.0,
                            "totalTrades", 0L,
                            "winningTrades", 0L),
                    "error", e.getMessage());
        }
    }
}
