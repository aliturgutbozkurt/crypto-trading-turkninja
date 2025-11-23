package com.turkninja.infra;

import com.binance.connector.client.utils.websocketcallback.WebSocketMessageCallback;
import com.turkninja.infra.repository.AccountRepository;
import org.bson.Document;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class SynchronizationService {
    private static final Logger logger = LoggerFactory.getLogger(SynchronizationService.class);
    private final FuturesBinanceService futuresService;
    private final AccountRepository accountRepository;
    private final ScheduledExecutorService scheduler;
    private final java.util.concurrent.ConcurrentHashMap<String, Double> latestPrices = new java.util.concurrent.ConcurrentHashMap<>();

    public SynchronizationService(FuturesBinanceService futuresService, AccountRepository accountRepository) {
        this.futuresService = futuresService;
        this.accountRepository = accountRepository;
        // Using Virtual Threads for the scheduler if running on Java 21+
        this.scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());
    }

    public void start() {
        logger.info("Starting Synchronization Service...");

        // 1. Initial Snapshot
        syncAccountData();

        // 2. Start WebSocket Streams (TODO: Implement Futures WebSocket)
        // For now, relying on periodic polling
        // futuresService doesn't have WebSocket support yet

        // futuresService.startUserDataStream(response -> {
        // logger.debug("User Data Update: {}", response);
        // Parse response and update UI/DB
        // For demo, we just log it. In reality, we'd update the AccountRepository and
        // notify UI.
        // Example:
        // JSONObject json = new JSONObject(response);
        // if (json.getString("e").equals("outboundAccountPosition")) { ... }
        // });

        // Example: Listen to BTCUSDT ticker
        // In a real app, we would subscribe to all relevant symbols
        // String[] symbols = {"BTCUSDT", "ETHUSDT", "BNBUSDT", "XRPUSDT", "ADAUSDT",
        // "DOGEUSDT", "SOLUSDT"};
        // for (String symbol : symbols) {
        // futuresService.startSymbolTickerStream(symbol, response -> {
        // try {
        // JSONObject json = new JSONObject(response);
        // // "c" is the last price in the ticker event
        // if (json.has("c")) {
        // double price = json.getDouble("c");
        // String s = json.getString("s");
        // latestPrices.put(s, price);
        // // logger.debug("Price Update: {} -> {}", s, price);
        // }
        // } catch (Exception e) {
        // logger.error("Error parsing ticker data: {}", response, e);
        // }
        // });
        // }

        // Keep polling as backup or for other data
        scheduler.scheduleAtFixedRate(this::syncAccountData, 0, 30, TimeUnit.SECONDS);
    }

    public Double getLatestPrice(String symbol) {
        return latestPrices.get(symbol);
    }

    private void syncAccountData() {
        try {
            String accountSnapshotJson = futuresService.getAccountInfo();
            // Parse JSON and save to DB
            // The response structure depends on the API.
            // For simplicity, we just save the raw JSON as a Document for now.
            // In a real app, we would parse it into a structured object.
            Document doc = Document.parse(accountSnapshotJson);
            accountRepository.saveSnapshot(doc);
            logger.debug("Account data synced: {}", accountSnapshotJson);
        } catch (Exception e) {
            logger.error("Error syncing account data", e);
        }
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}
