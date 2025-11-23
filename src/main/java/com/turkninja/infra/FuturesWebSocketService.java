package com.turkninja.infra;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * WebSocket service for Binance Futures real-time data streaming
 * Provides user data stream, mark price, and kline updates
 */
public class FuturesWebSocketService {
    private static final Logger logger = LoggerFactory.getLogger(FuturesWebSocketService.class);
    private static final String WS_BASE_URL = "wss://fstream.binance.com";

    private final OkHttpClient httpClient;
    private final String apiKey;
    private final String secretKey;

    private WebSocket userDataWebSocket;
    private WebSocket markPriceWebSocket;
    private WebSocket klineWebSocket;
    private String listenKey;

    private Consumer<JSONObject> accountUpdateListener;
    private Consumer<JSONObject> positionUpdateListener;
    private Consumer<JSONObject> orderUpdateListener;
    private Consumer<JSONObject> markPriceUpdateListener;

    // Data caches (to avoid REST API calls)
    private volatile JSONObject cachedAccountInfo;
    private volatile JSONArray cachedPositions;
    private final Map<String, LinkedList<JSONObject>> klineCache = new ConcurrentHashMap<>();
    private final List<Consumer<JSONArray>> positionCacheListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Set account info cache (used for initial REST fallback).
     */
    public void setCachedAccountInfo(JSONObject accountInfo) {
        this.cachedAccountInfo = accountInfo;
    }

    /**
     * Set positions cache (used for initial REST fallback).
     */
    public void setCachedPositions(JSONArray positions) {
        this.cachedPositions = positions;
        notifyPositionCacheListeners();
    }

    public void addPositionCacheListener(Consumer<JSONArray> listener) {
        positionCacheListeners.add(listener);
    }

    private void notifyPositionCacheListeners() {
        if (cachedPositions != null) {
            for (Consumer<JSONArray> listener : positionCacheListeners) {
                try {
                    listener.accept(cachedPositions);
                } catch (Exception e) {
                    logger.error("Error in position cache listener", e);
                }
            }
        }
    }

    /**
     * Add a list of klines to the cache for a symbol.
     * This is used for the initial REST fallback before WebSocket streams fill the
     * cache.
     */
    public void addKlinesToCache(String symbol, List<JSONObject> klines) {
        LinkedList<JSONObject> list = klineCache.computeIfAbsent(symbol, k -> new LinkedList<>());
        list.clear();
        list.addAll(klines);
        // Trim to max size
        while (list.size() > MAX_KLINES_PER_SYMBOL) {
            list.removeFirst();
        }
    }

    private static final int MAX_KLINES_PER_SYMBOL = 100;

    public FuturesWebSocketService(String apiKey, String secretKey) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        logger.info("Futures WebSocket Service initialized");
    }

    /**
     * Start user data stream (account updates, position changes, order updates)
     */
    public void startUserDataStream() {
        try {
            // Get listen key from REST API
            listenKey = getListenKey();
            if (listenKey == null) {
                logger.error("Failed to get listen key");
                return;
            }

            // Connect to user data stream
            String wsUrl = WS_BASE_URL + "/ws/" + listenKey;
            Request request = new Request.Builder().url(wsUrl).build();

            userDataWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    logger.info("User data stream connected");
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    handleUserDataMessage(text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    logger.error("User data stream failed", t);
                    // Attempt reconnection after 5 seconds
                    scheduleReconnect();
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    logger.warn("User data stream closed: {} - {}", code, reason);
                }
            });

            // Keep listen key alive (ping every 30 minutes)
            startListenKeyKeepAlive();

        } catch (Exception e) {
            logger.error("Failed to start user data stream", e);
        }
    }

    /**
     * Start Kline (candlestick) stream for technical analysis
     */
    public void startKlineStream(List<String> symbols) {
        try {
            // Build combined stream URL for 1-minute klines
            StringBuilder streamBuilder = new StringBuilder(WS_BASE_URL + "/stream?streams=");
            for (int i = 0; i < symbols.size(); i++) {
                if (i > 0)
                    streamBuilder.append("/");
                streamBuilder.append(symbols.get(i).toLowerCase()).append("@kline_1m");
            }

            String wsUrl = streamBuilder.toString();
            Request request = new Request.Builder().url(wsUrl).build();

            klineWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    logger.info("Kline stream connected for {} symbols", symbols.size());
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    handleKlineMessage(text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    logger.error("Kline stream failed", t);
                }
            });

        } catch (Exception e) {
            logger.error("Failed to start kline stream", e);
        }
    }

    /**
     * Start mark price stream for liquidation monitoring
     */
    public void startMarkPriceStream(String... symbols) {
        try {
            // Build combined stream URL
            StringBuilder streamBuilder = new StringBuilder(WS_BASE_URL + "/stream?streams=");
            for (int i = 0; i < symbols.length; i++) {
                if (i > 0)
                    streamBuilder.append("/");
                streamBuilder.append(symbols[i].toLowerCase()).append("@markPrice@1s");
            }

            String wsUrl = streamBuilder.toString();
            Request request = new Request.Builder().url(wsUrl).build();

            markPriceWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    logger.info("Mark price stream connected for {} symbols", symbols.length);
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    handleMarkPriceMessage(text);
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    logger.error("Mark price stream failed", t);
                }
            });

        } catch (Exception e) {
            logger.error("Failed to start mark price stream", e);
        }
    }

    /**
     * Get listen key for user data stream
     */
    private String getListenKey() {
        try {
            Request request = new Request.Builder()
                    .url("https://fapi.binance.com/fapi/v1/listenKey")
                    .post(RequestBody.create("", null))
                    .addHeader("X-MBX-APIKEY", apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject json = new JSONObject(response.body().string());
                    return json.getString("listenKey");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to get listen key", e);
        }
        return null;
    }

    /**
     * Keep listen key alive by sending PUT request every 30 minutes
     */
    private void startListenKeyKeepAlive() {
        new Thread(() -> {
            while (userDataWebSocket != null) {
                try {
                    Thread.sleep(30 * 60 * 1000); // 30 minutes

                    Request request = new Request.Builder()
                            .url("https://fapi.binance.com/fapi/v1/listenKey")
                            .put(RequestBody.create("", null))
                            .addHeader("X-MBX-APIKEY", apiKey)
                            .build();

                    try (Response response = httpClient.newCall(request).execute()) {
                        if (response.isSuccessful()) {
                            logger.debug("Listen key kept alive");
                        } else {
                            logger.warn("Failed to keep listen key alive");
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Error keeping listen key alive", e);
                }
            }
        }, "ListenKeyKeepAlive").start();
    }

    /**
     * Handle user data stream messages
     */
    private void handleUserDataMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String eventType = json.getString("e");

            switch (eventType) {
                case "ACCOUNT_UPDATE":
                    logger.debug("Account update received");

                    // Update cached account info
                    if (json.has("a")) {
                        JSONObject accountData = json.getJSONObject("a");

                        // Build account info cache
                        if (cachedAccountInfo == null) {
                            cachedAccountInfo = new JSONObject();
                        }

                        // Update balances
                        if (accountData.has("B")) {
                            JSONArray balances = accountData.getJSONArray("B");
                            for (int i = 0; i < balances.length(); i++) {
                                JSONObject balance = balances.getJSONObject(i);
                                if ("USDT".equals(balance.getString("a"))) {
                                    cachedAccountInfo.put("totalWalletBalance", balance.getDouble("wb"));
                                    cachedAccountInfo.put("totalMarginBalance", balance.getDouble("cw"));
                                    cachedAccountInfo.put("availableBalance", balance.getDouble("bc"));
                                }
                            }
                        }

                        // Update positions cache
                        if (accountData.has("P")) {
                            JSONArray positions = accountData.getJSONArray("P");
                            cachedPositions = new JSONArray();

                            for (int i = 0; i < positions.length(); i++) {
                                JSONObject pos = positions.getJSONObject(i);
                                double posAmt = pos.getDouble("pa");

                                // Only include non-zero positions
                                if (posAmt != 0) {
                                    JSONObject position = new JSONObject();
                                    position.put("symbol", pos.getString("s"));
                                    position.put("positionAmt", posAmt);
                                    position.put("entryPrice", pos.getDouble("ep"));
                                    position.put("unRealizedProfit", pos.getDouble("up"));
                                    cachedPositions.put(position);
                                }
                            }

                            logger.debug("Cached {} positions", cachedPositions.length());
                            notifyPositionCacheListeners();
                        }
                    }

                    if (accountUpdateListener != null) {
                        accountUpdateListener.accept(json);
                    }
                    break;

                case "ORDER_TRADE_UPDATE":
                    logger.debug("Order update received");
                    if (orderUpdateListener != null) {
                        orderUpdateListener.accept(json);
                    }
                    break;

                case "ACCOUNT_CONFIG_UPDATE":
                    logger.debug("Account config update received");
                    break;

                default:
                    logger.debug("Unknown event type: {}", eventType);
            }

            // Notify position update listener
            if (eventType.equals("ACCOUNT_UPDATE") && json.has("a")) {
                JSONObject accountData = json.getJSONObject("a");
                if (accountData.has("P") && positionUpdateListener != null) {
                    positionUpdateListener.accept(json);
                }
            }

        } catch (Exception e) {
            logger.error("Error handling user data message", e);
        }
    }

    /**
     * Handle mark price stream messages
     */
    private void handleMarkPriceMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");
                if (markPriceUpdateListener != null) {
                    markPriceUpdateListener.accept(data);
                }
            }
        } catch (Exception e) {
            logger.error("Error handling mark price message", e);
        }
    }

    /**
     * Handle kline stream messages and update cache
     */
    private void handleKlineMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");
                JSONObject kline = data.getJSONObject("k");
                String symbol = kline.getString("s");

                // Only store closed candles
                if (kline.getBoolean("x")) {
                    // Convert to simplified format
                    JSONObject simplifiedKline = new JSONObject();
                    simplifiedKline.put("openTime", kline.getLong("t"));
                    simplifiedKline.put("open", kline.getString("o"));
                    simplifiedKline.put("high", kline.getString("h"));
                    simplifiedKline.put("low", kline.getString("l"));
                    simplifiedKline.put("close", kline.getString("c"));
                    simplifiedKline.put("volume", kline.getString("v"));
                    simplifiedKline.put("closeTime", kline.getLong("T"));

                    // Add to cache
                    klineCache.computeIfAbsent(symbol, k -> new LinkedList<>());
                    LinkedList<JSONObject> klines = klineCache.get(symbol);
                    klines.addLast(simplifiedKline);

                    // Keep only last MAX_KLINES_PER_SYMBOL
                    while (klines.size() > MAX_KLINES_PER_SYMBOL) {
                        klines.removeFirst();
                    }

                    logger.debug("Kline cached for {}: {} candles", symbol, klines.size());
                }
            }
        } catch (Exception e) {
            logger.error("Error handling kline message", e);
        }
    }

    /**
     * Get cached klines for a symbol (avoids REST API call)
     */
    public List<JSONObject> getCachedKlines(String symbol, int limit) {
        LinkedList<JSONObject> klines = klineCache.get(symbol);
        if (klines == null || klines.isEmpty()) {
            logger.warn("No cached klines for {}", symbol);
            return Collections.emptyList();
        }

        int size = klines.size();
        int fromIndex = Math.max(0, size - limit);
        return new ArrayList<>(klines.subList(fromIndex, size));
    }

    /**
     * Get cached account info (avoids REST API call)
     */
    public JSONObject getCachedAccountInfo() {
        return cachedAccountInfo;
    }

    /**
     * Get cached positions (avoids REST API call)
     */
    public JSONArray getCachedPositions() {
        return cachedPositions;
    }

    /**
     * Check if cache is ready
     */
    public boolean isCacheReady() {
        return cachedAccountInfo != null && cachedPositions != null;
    }

    /**
     * Schedule reconnection after failure
     */
    private void scheduleReconnect() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                logger.info("Attempting to reconnect user data stream");
                startUserDataStream();
            } catch (InterruptedException e) {
                logger.error("Reconnect interrupted", e);
            }
        }, "WebSocketReconnect").start();
    }

    /**
     * Set listener for account updates
     */
    public void setAccountUpdateListener(Consumer<JSONObject> listener) {
        this.accountUpdateListener = listener;
    }

    /**
     * Set listener for position updates
     */
    public void setPositionUpdateListener(Consumer<JSONObject> listener) {
        this.positionUpdateListener = listener;
    }

    /**
     * Set listener for order updates
     */
    public void setOrderUpdateListener(Consumer<JSONObject> listener) {
        this.orderUpdateListener = listener;
    }

    /**
     * Set listener for mark price updates
     */
    public void setMarkPriceUpdateListener(Consumer<JSONObject> listener) {
        this.markPriceUpdateListener = listener;
    }

    /**
     * Close all WebSocket connections
     */
    public void close() {
        if (userDataWebSocket != null) {
            userDataWebSocket.close(1000, "Closing");
            userDataWebSocket = null;
        }
        if (markPriceWebSocket != null) {
            markPriceWebSocket.close(1000, "Closing");
            markPriceWebSocket = null;
        }
        logger.info("WebSocket connections closed");
    }
}
