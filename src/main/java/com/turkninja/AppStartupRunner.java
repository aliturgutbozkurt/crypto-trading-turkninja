package com.turkninja;

import com.turkninja.engine.PositionTracker;
import com.turkninja.engine.RiskManager;
import com.turkninja.engine.StrategyEngine;
import com.turkninja.engine.OrderBookService;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;

import com.turkninja.infra.TelegramNotifier;
import com.turkninja.web.service.WebSocketPushService;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

@Component
public class AppStartupRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AppStartupRunner.class);

    private final FuturesBinanceService futuresBinanceService;
    private final FuturesWebSocketService webSocketService;
    private final PositionTracker positionTracker;
    private final RiskManager riskManager;
    private final StrategyEngine strategyEngine;
    private final OrderBookService orderBookService;
    private final WebSocketPushService webSocketPushService;
    private final TelegramNotifier telegramNotifier;

    private final ScheduledExecutorService statusScheduler = Executors.newSingleThreadScheduledExecutor();

    public AppStartupRunner(FuturesBinanceService futuresBinanceService,
            FuturesWebSocketService webSocketService,
            PositionTracker positionTracker,
            RiskManager riskManager,
            StrategyEngine strategyEngine,
            OrderBookService orderBookService,
            WebSocketPushService webSocketPushService,
            TelegramNotifier telegramNotifier) {
        this.futuresBinanceService = futuresBinanceService;
        this.webSocketService = webSocketService;
        this.positionTracker = positionTracker;
        this.riskManager = riskManager;
        this.strategyEngine = strategyEngine;
        this.orderBookService = orderBookService;
        this.webSocketPushService = webSocketPushService;
        this.telegramNotifier = telegramNotifier;
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

                List<String> symbols = Arrays.asList("BTCUSDT", "ETHUSDT", "ATOMUSDT",
                        "SOLUSDT", "DOGEUSDT", "XRPUSDT", "ALGOUSDT",
                        "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT",
                        "ADAUSDT", "NEARUSDT", "SANDUSDT", "MANAUSDT", "ARBUSDT");
                for (String sym : symbols) {
                    // Fetch 15m klines and populate cache
                    loadKlinesToCache(sym, "15m");
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
            // Start Kline stream for all symbols (5m candles)
            List<String> klineSymbols = Arrays.asList("BTCUSDT", "ETHUSDT", "ATOMUSDT", "SOLUSDT", "DOGEUSDT",
                    "XRPUSDT", "ALGOUSDT", "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT",
                    "ADAUSDT", "NEARUSDT", "SANDUSDT", "MANAUSDT", "ARBUSDT");
            webSocketService.startKlineStream(klineSymbols);
            logger.info("Kline stream started");

            // Start Depth (Order Book) stream for all trading symbols
            webSocketService.setOrderBookService(orderBookService);
            List<String> depthSymbols = strategyEngine.getTradingSymbols();
            // Initialize order books first
            for (String sym : depthSymbols) {
                orderBookService.initializeOrderBook(sym);
            }
            webSocketService.startDepthStream(depthSymbols);
            logger.info("📊 Order Book (Depth) stream started for {} signals", depthSymbols.size());

            // Connect WebSocket push service to StrategyEngine for real-time signal
            // notifications
            strategyEngine.setWebSocketPushService(webSocketPushService);

            // Start automated trading
            strategyEngine.startAutomatedTrading();
            logger.info("Automated trading started");

            // Start Mark Price Stream for all trading symbols (needed for Web UI PnL)
            List<String> symbols = strategyEngine.getTradingSymbols();
            webSocketService.startMarkPriceStream(symbols.toArray(new String[0]));
            logger.info("Started Mark Price Stream for {} symbols", symbols.size());

            // CRITICAL: Sync existing positions with RiskManager BEFORE starting monitoring
            // This ensures trailing stops work for positions opened before restart
            try {
                JSONArray currentPositions = webSocketService.getCachedPositions();
                if (currentPositions != null && currentPositions.length() > 0) {
                    logger.info("🔄 Syncing {} existing positions with RiskManager for trailing stop monitoring...",
                            currentPositions.length());
                    positionTracker.syncPositions(currentPositions);
                    logger.info("✅ Existing positions registered with RiskManager");
                }
            } catch (Exception e) {
                logger.error("Failed to sync existing positions: {}", e.getMessage());
            }

            // Start Risk Manager monitoring
            riskManager.startMonitoring();

            // Register RiskManager for real-time trailing stop checks on mark price updates
            webSocketService.setMarkPriceUpdateListener(priceUpdate -> {
                try {
                    String symbol = priceUpdate.getString("s");
                    double markPrice = priceUpdate.getDouble("p");
                    riskManager.checkPositionOnPriceUpdate(symbol, markPrice);
                } catch (Exception e) {
                    logger.error("Error processing mark price update for trailing stop", e);
                }
            });
            logger.info("✅ Real-time trailing stop monitoring enabled via mark price stream");

            logger.info("✅ System Fully Initialized and Running");
            logger.info("✅ Web UI available at http://localhost:8080");

            // Schedule periodic status report (every 5 minutes)
            statusScheduler.scheduleAtFixedRate(() -> {
                try {
                    int positionCount = positionTracker.getAllPositions().size();
                    double balance = futuresBinanceService.getAvailableBalance();

                    String message = String.format("📊 Status Report:\nPositions: %d\nBalance: $%.2f",
                            positionCount, balance);

                    telegramNotifier.sendAlert(TelegramNotifier.AlertLevel.INFO, message);
                } catch (Exception e) {
                    logger.error("Failed to send periodic status report", e);
                }
            }, 5, 5, TimeUnit.MINUTES);

            logger.info("✅ Periodic status reporting enabled (every 5 minutes)");

        } catch (Throwable t) {
            logger.error("Initialization failed", t);
            System.err.println("CRITICAL INITIALIZATION FAILURE: " + t.getMessage());
            t.printStackTrace();
        }
    }

    /**
     * Helper method to load klines from REST API and add to WebSocket cache
     */
    private void loadKlinesToCache(String symbol, String interval) {
        try {
            String klinesJson = futuresBinanceService.getKlines(symbol, interval, 100);
            org.json.JSONArray klinesArr = new org.json.JSONArray(klinesJson);
            List<org.json.JSONObject> klineList = new ArrayList<>();

            for (int i = 0; i < klinesArr.length(); i++) {
                org.json.JSONArray klineData = klinesArr.getJSONArray(i);

                // Convert array format to JSONObject format expected by FuturesWebSocketService
                org.json.JSONObject simplifiedKline = new org.json.JSONObject();
                simplifiedKline.put("openTime", klineData.getLong(0));
                simplifiedKline.put("open", klineData.getString(1));
                simplifiedKline.put("high", klineData.getString(2));
                simplifiedKline.put("low", klineData.getString(3));
                simplifiedKline.put("close", klineData.getString(4));
                simplifiedKline.put("volume", klineData.getString(5));
                simplifiedKline.put("closeTime", klineData.getLong(6));

                klineList.add(simplifiedKline);
            }

            webSocketService.addKlinesToCache(symbol, interval, klineList);
            logger.info("Loaded {} {} klines for {}", klineList.size(), interval, symbol);
        } catch (Exception e) {
            logger.warn("Failed to load {} klines for {}: {}", interval, symbol, e.getMessage());
        }
    }
}
