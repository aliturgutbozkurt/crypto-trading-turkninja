package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.IndicatorService;
import com.turkninja.engine.PositionTracker;
import com.turkninja.engine.RiskManager;
import com.turkninja.engine.StrategyEngine;
import com.turkninja.infra.*;
import com.turkninja.infra.repository.AccountRepository;
import com.turkninja.infra.repository.TradeRepository;
import com.turkninja.ui.DashboardController;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * JavaFX App - Futures Trading with 20x Leverage
 */
public class App extends Application {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    private SynchronizationService syncService;
    private DatabaseService databaseService;
    private FuturesBinanceService futuresBinanceService;
    private FuturesWebSocketService webSocketService;
    private RiskManager riskManager;
    private PositionTracker positionTracker;
    private StrategyEngine strategyEngine;

    @Override
    public void start(Stage stage) throws IOException {
        // Initialize UI first
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource("/com/turkninja/ui/dashboard.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 820, 640);
        DashboardController controller = fxmlLoader.getController();

        stage.setTitle("Crypto Trading Bot - Futures 20x Leverage (CROSS MARGIN) - AUTOMATED");
        stage.setScene(scene);
        stage.show();

        // Run initialization in a background thread
        new Thread(() -> {
            try {
                logger.info("Initializing services...");
                Platform.runLater(() -> controller.updateStatus("Initializing services..."));

                // Initialize Database
                databaseService = new DatabaseService(
                        Config.get(Config.MONGODB_URI, "mongodb://localhost:27017"),
                        Config.get(Config.DB_NAME, "crypto_trading"));
                AccountRepository accountRepository = new AccountRepository(databaseService);
                TradeRepository tradeRepository = new TradeRepository(databaseService);

                // Initialize Futures service for 20x leverage (BLOCKING)
                futuresBinanceService = new FuturesBinanceService();
                boolean dryRun = Boolean.parseBoolean(Config.get(Config.DRY_RUN, "false"));
                futuresBinanceService.setDryRun(dryRun);

                // Initialize Risk Manager first (needed by PositionTracker)
                riskManager = new RiskManager(null, futuresBinanceService); // Temporarily null

                // Initialize Position Tracker with RiskManager
                positionTracker = new PositionTracker(tradeRepository, riskManager);

                // Update RiskManager with PositionTracker
                riskManager.setPositionTracker(positionTracker);

                // Initialize WebSocket for real-time data
                webSocketService = new FuturesWebSocketService(
                        Config.get(Config.BINANCE_API_KEY),
                        Config.get(Config.BINANCE_SECRET_KEY));

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

                            String side = amount > 0 ? "BUY" : "SELL"; // Binance uses positive for LONG, negative for
                                                                       // SHORT

                            // Track position (this registers it with RiskManager)
                            positionTracker.trackPosition(symbol, side, entryPrice, Math.abs(amount));
                            logger.info("Restored position tracking for {}", symbol);
                        }
                    }

                    // Klines for each symbol (use 1h interval, 100 candles)
                    List<String> symbols = Arrays.asList("BTCUSDT", "ETHUSDT",
                            "SOLUSDT", "DOGEUSDT", "XRPUSDT", "BCHUSDT");
                    for (String sym : symbols) {
                        String klinesJson = futuresBinanceService.getKlines(sym, "1h", 100);
                        // Convert JSON array string to List<JSONObject>
                        // Binance returns klines as nested arrays: [[timestamp, open, high, low, close,
                        // volume, ...], ...]
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
                // Start Kline stream for all symbols (1m candles)
                List<String> klineSymbols = Arrays.asList("BTCUSDT", "ETHUSDT", "ATOMUSDT", "SOLUSDT", "DOGEUSDT",
                        "XRPUSDT", "BCHUSDT");
                webSocketService.startKlineStream(klineSymbols);
                logger.info("Kline stream started");

                // Initialize Synchronization Service
                syncService = new SynchronizationService(futuresBinanceService, accountRepository);
                logger.info("Sync service initialized");

                // Initialize Strategy Engine
                IndicatorService indicatorService = new IndicatorService();
                this.strategyEngine = new StrategyEngine(futuresBinanceService, webSocketService,
                        indicatorService, riskManager, positionTracker);
                logger.info("Strategy engine initialized");

                // Start automated trading
                strategyEngine.startAutomatedTrading();
                logger.info("Automated trading started (Dry Run: {})", dryRun);

                // Start Sync
                syncService.start();
                logger.info("Sync service started");

                // Start Risk Manager monitoring
                riskManager.startMonitoring();

                // Pass services to controller
                Platform.runLater(() -> {
                    controller.setServices(futuresBinanceService, webSocketService,
                            syncService, riskManager,
                            strategyEngine, positionTracker);
                    controller.updateStatus(
                            "System: Running | Strategy: " + (strategyEngine.isTradingActive() ? "Active" : "Stopped"));
                });

            } catch (Throwable t) {
                logger.error("Initialization failed", t);
                System.err.println("CRITICAL INITIALIZATION FAILURE: " + t.getMessage());
                t.printStackTrace();
                Platform.runLater(() -> controller.updateStatus("Initialization Failed: " + t.getMessage()));
            }
        }).start();
    }

    @Override
    public void stop() throws Exception {
        System.out.println("Shutting down...");

        // Stop strategy engine
        if (strategyEngine != null) {
            strategyEngine.shutdown();
        }

        // Stop risk monitoring
        if (riskManager != null) {
            riskManager.shutdown();
        }

        // Close WebSocket connections
        if (webSocketService != null) {
            webSocketService.close();
        }

        // Stop synchronization
        if (syncService != null) {
            syncService.stop();
        }

        // Close database
        if (databaseService != null) {
            databaseService.close();
        }

        super.stop();
        System.out.println("Shutdown complete");
    }

    public static void main(String[] args) {
        launch();
    }

}