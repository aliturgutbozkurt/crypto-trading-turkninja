package com.turkninja.web.controller;

import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.engine.PositionTracker;
import com.turkninja.engine.RiskManager;
import com.turkninja.engine.StrategyEngine;
import com.turkninja.web.dto.PositionDTO;
import com.turkninja.web.dto.AccountDTO;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DashboardRestController {

    private final FuturesBinanceService futuresService;
    private final FuturesWebSocketService webSocketService;
    private final PositionTracker positionTracker;
    private final RiskManager riskManager;
    private final StrategyEngine strategyEngine;

    public DashboardRestController(FuturesBinanceService futuresService,
            FuturesWebSocketService webSocketService,
            PositionTracker positionTracker,
            RiskManager riskManager,
            StrategyEngine strategyEngine) {
        this.futuresService = futuresService;
        this.webSocketService = webSocketService;
        this.positionTracker = positionTracker;
        this.riskManager = riskManager;
        this.strategyEngine = strategyEngine;
    }

    @GetMapping("/positions")
    public List<PositionDTO> getPositions() {
        List<PositionDTO> positions = new ArrayList<>();
        try {
            JSONArray positionsArray = webSocketService.getCachedPositions();
            JSONObject account = webSocketService.getCachedAccountInfo();
            double balance = account != null ? account.optDouble("totalMarginBalance", 0.0) : 0.0;

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

                        positions.add(new PositionDTO(symbol, side, entryPrice, markPrice,
                                unrealizedProfit, roiPercent, notional, balancePercent));
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
}
