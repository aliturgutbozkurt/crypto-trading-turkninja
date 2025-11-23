package com.turkninja.infra;

import com.binance.connector.client.SpotClient;
import com.binance.connector.client.impl.SpotClientImpl;
import com.binance.connector.client.impl.WebSocketStreamClientImpl;
import com.binance.connector.client.utils.websocketcallback.WebSocketMessageCallback;
import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;

public class BinanceService {
    private static final Logger logger = LoggerFactory.getLogger(BinanceService.class);
    private final SpotClient spotClient;
    private final WebSocketStreamClientImpl wsClient;
    private String listenKey;

    public BinanceService() {
        String apiKey = Config.get(Config.BINANCE_API_KEY);
        String secretKey = Config.get(Config.BINANCE_SECRET_KEY);

        if (apiKey == null || secretKey == null) {
            logger.warn("Binance API Keys not found. Using dummy keys for simulation/view-only mode.");
            apiKey = "dummy_api_key";
            secretKey = "dummy_secret_key";
        }

        this.spotClient = new SpotClientImpl(apiKey, secretKey);
        this.wsClient = new WebSocketStreamClientImpl(); // Public streams don't need keys
        logger.info("Binance Client initialized");
    }

    public SpotClient getClient() {
        return spotClient;
    }

    public String getExchangeInfo() {
        return spotClient.createMarket().exchangeInfo(new LinkedHashMap<>());
    }

    public String getAccountSnapshot() {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "SPOT");
        return spotClient.createWallet().accountSnapshot(parameters);
    }

    public String getKlines(String symbol, String interval, int limit) {
        LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("symbol", symbol);
        parameters.put("interval", interval);
        parameters.put("limit", limit);
        return spotClient.createMarket().klines(parameters);
    }

    public void startUserDataStream(WebSocketMessageCallback onUpdate) {
        try {
            // 1. Get ListenKey
            this.listenKey = spotClient.createUserData().createListenKey();
            logger.info("ListenKey created: {}", listenKey);

            // 2. Start Stream
            wsClient.listenUserStream(listenKey, onUpdate);

            // Note: In a real app, we need a KeepAlive task for the ListenKey every 30
            // mins.
        } catch (Exception e) {
            logger.error("Failed to start User Data Stream", e);
        }
    }

    public void startSymbolTickerStream(String symbol, WebSocketMessageCallback onUpdate) {
        wsClient.symbolTicker(symbol.toLowerCase(), onUpdate);
    }

    public void close() {
        wsClient.closeAllConnections();
    }
}
