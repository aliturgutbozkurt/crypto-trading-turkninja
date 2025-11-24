package com.turkninja;

import com.turkninja.engine.PositionTracker;
import com.turkninja.engine.RiskManager;
import com.turkninja.engine.StrategyEngine;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.infra.SynchronizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class AppStartupRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AppStartupRunner.class);

    private final FuturesBinanceService futuresBinanceService;
    private final FuturesWebSocketService webSocketService;
    private final PositionTracker positionTracker;
    private final RiskManager riskManager;
    private final StrategyEngine strategyEngine;
    private final SynchronizationService syncService;

    public AppStartupRunner(FuturesBinanceService futuresBinanceService,
            FuturesWebSocketService webSocketService,
            PositionTracker positionTracker,
            RiskManager riskManager,
            StrategyEngine strategyEngine,
            SynchronizationService syncService) {
        this.futuresBinanceService = futuresBinanceService;
        this.webSocketService = webSocketService;
        this.positionTracker = positionTracker;
        this.riskManager = riskManager;
        this.strategyEngine = strategyEngine;
        this.syncService = syncService;
    }

    @Override
    public void run(String... args) throws Exception {
        try {
            logger.info("Initializing services...");

            // ---- REST fallback: populate initial caches ----
            try {
                // Account info
                String accountJson = futuresBinanceService.getAccountInfo();
                org.json.JSONObject accountObj = new org.json.JSONObject(accountJson);
                webSocketService.setCachedAccountInfo(accountObj);
                logger.info("Cached account info: balance=${}", accountObj.optDouble("totalWalletBalance", 0.0));

                // Positions
                String positionsJson = futuresBinanceService.getPositionInfo();
                org.json.JSONArray positionsArr = new org.json.JSONArray(positionsJson);
                webSocketService.setCachedPositions(positionsArr);
                logger.info("Cached {} positions from REST API", positionsArr.length());

                // Populate PositionTracker with existing active positions
                for (int i = 0; i < positionsArr.length(); i++) {
                    org.json.JSONObject pos = positionsArr.getJSONObject(i);
                    double amount = pos.getDouble("positionAmt");
                    if (amount != 0) {
                        String symbol = pos.getString("symbol");
                        double entryPrice = pos.getDouble("entryPrice");

                        // Ignore dust positions (< $5)
                        double notionalValue = Math.abs(amount * entryPrice);
                        if (notionalValue < 5.0) {
                            logger.info("Ignoring dust position on startup: {} (Value: ${})", symbol,
                                    notionalValue);
                            continue;
                        }

                        String side = amount > 0 ? "BUY" : "SELL"; // Binance uses positive for LONG, negative for SHORT

                        // Track position (this registers it with RiskManager)
                        positionTracker.trackPosition(symbol, side, entryPrice, Math.abs(amount));
                        logger.info("Restored position tracking for {}", symbol);
                    }
                }

                // Klines for each symbol (use 15m interval, 100 candles)
                List<String> symbols = Arrays.asList("BTCUSDT", "ETHUSDT", "ATOMUSDT",
                        "SOLUSDT", "DOGEUSDT", "XRPUSDT", "BCHUSDT", "ALGOUSDT",
                        "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT");
                for (String sym : symbols) {
                    String klinesJson = futuresBinanceService.getKlines(sym, "15m", 100);
                    // Convert JSON array string to List<JSONObject>
                    org.json.JSONArray arr = new org.json.JSONArray(klinesJson);
                    java.util.List<org.json.JSONObject> list = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONArray klineArr = arr.getJSONArray(i);
                        // Convert array to object with named fields
                        org.json.JSONObject klineObj = new org.json.JSONObject();
                        klineObj.put("openTime", klineArr.getLong(0)); // Open time
                        klineObj.put("open", klineArr.getString(1)); // Open
                        klineObj.put("high", klineArr.getString(2)); // High
                        klineObj.put("low", klineArr.getString(3)); // Low
                        klineObj.put("close", klineArr.getString(4)); // Close
                        klineObj.put("volume", klineArr.getString(5)); // Volume
                        klineObj.put("closeTime", klineArr.getLong(6)); // Close time
                        list.add(klineObj);
                    }
                    webSocketService.addKlinesToCache(sym, list);
                    logger.info("Loaded {} klines for {} from REST API", list.size(), sym);
                }
            } catch (Exception e) {
                logger.warn("REST fallback failed to populate caches: {}", e.getMessage());
            }

            // Start WebSocket streams (BLOCKING)
            webSocketService.startUserDataStream();
            logger.info("User data stream started");

            // Subscribe PositionTracker to WebSocket updates (Syncs internal state with
            // real-time data)
            webSocketService.addPositionCacheListener(positions -> {
                positionTracker.syncPositions(positions);
            });
            // Start Kline stream for all symbols (15m candles)
            List<String> klineSymbols = Arrays.asList("BTCUSDT", "ETHUSDT", "ATOMUSDT", "SOLUSDT", "DOGEUSDT",
                    "XRPUSDT", "BCHUSDT", "ALGOUSDT", "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT");
            webSocketService.startKlineStream(klineSymbols);
            logger.info("Kline stream started");

            // Start automated trading
            strategyEngine.startAutomatedTrading();
            logger.info("Automated trading started");

            // Start Mark Price Stream for all trading symbols (needed for Web UI PnL)
            List<String> symbols = strategyEngine.getTradingSymbols();
            webSocketService.startMarkPriceStream(symbols.toArray(new String[0]));
            logger.info("Started Mark Price Stream for {} symbols", symbols.size());

            // Start Sync
            syncService.start();
            logger.info("Sync service started");

            // Start Risk Manager monitoring
            riskManager.startMonitoring();

            logger.info("✅ System Fully Initialized and Running");
            logger.info("✅ Web UI available at http://localhost:8080");

        } catch (Throwable t) {
            logger.error("Initialization failed", t);
            System.err.println("CRITICAL INITIALIZATION FAILURE: " + t.getMessage());
            t.printStackTrace();
        }
    }
}
