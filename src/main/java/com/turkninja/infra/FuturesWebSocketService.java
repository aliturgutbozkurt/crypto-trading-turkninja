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
    private WebSocket depthWebSocket;
    private String listenKey;

    private Consumer<JSONObject> accountUpdateListener;
    private Consumer<JSONObject> positionUpdateListener;
    private Consumer<JSONObject> orderUpdateListener;
    private Consumer<JSONObject> markPriceUpdateListener;
    private Consumer<JSONObject> klineUpdateListener;

    // Data caches (to avoid REST API calls)
    private volatile JSONObject cachedAccountInfo;
    private volatile JSONArray cachedPositions;
    private final Map<String, LinkedList<JSONObject>> klineCache = new ConcurrentHashMap<>();
    private final List<Consumer<JSONArray>> positionCacheListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Double> markPriceCache = new ConcurrentHashMap<>();

    // Order Book Service (for depth stream)
    private com.turkninja.engine.OrderBookService orderBookService;

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
     * Add a list of klines to the cache for a symbol and interval.
     * This is used for the initial REST fallback before WebSocket streams fill the
     * cache.
     */
    public void addKlinesToCache(String symbol, String interval, List<JSONObject> klines) {
        String cacheKey = symbol + "_" + interval;
        LinkedList<JSONObject> list = klineCache.computeIfAbsent(cacheKey, k -> new LinkedList<>());
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
    /**
     * Start Kline (candlestick) stream for technical analysis
     * Subscribes to both 1m and 5m candles for all symbols
     */
    public void startKlineStream(List<String> symbols) {
        try {
            // Build combined stream URL for 15-minute klines only
            StringBuilder streamBuilder = new StringBuilder(WS_BASE_URL + "/stream?streams=");
            for (int i = 0; i < symbols.size(); i++) {
                if (i > 0) {
                    streamBuilder.append("/");
                }
                String symbol = symbols.get(i).toLowerCase();
                streamBuilder.append(symbol).append("@kline_15m");
            }

            String wsUrl = streamBuilder.toString();
            Request request = new Request.Builder().url(wsUrl).build();

            klineWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    logger.info("Kline stream connected for {} symbols (15m)", symbols.size());
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

    // ... (existing code) ...

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
                String interval = kline.getString("i"); // Get interval (1m, 5m, etc.)

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

                    // Add to cache (Key: SYMBOL_INTERVAL)
                    String cacheKey = symbol + "_" + interval;
                    klineCache.computeIfAbsent(cacheKey, k -> new LinkedList<>());
                    LinkedList<JSONObject> klines = klineCache.get(cacheKey);
                    klines.addLast(simplifiedKline);

                    // Keep only last MAX_KLINES_PER_SYMBOL
                    while (klines.size() > MAX_KLINES_PER_SYMBOL) {
                        klines.removeFirst();
                    }

                    logger.debug("Kline cached for {}: {} candles", cacheKey, klines.size());

                    // Notify listener about closed candle (for signal generation)
                    if (klineUpdateListener != null) {
                        // Pass the simplified kline with symbol info
                        simplifiedKline.put("s", symbol);
                        simplifiedKline.put("i", interval);
                        klineUpdateListener.accept(simplifiedKline);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error handling kline message", e);
        }
    }

    /**
     * Retrieve a new listen key via Binance REST API.
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
            logger.error("Failed to obtain listen key", e);
        }
        return null;
    }

    /**
     * Handle incoming user data stream messages.
     */
    private void handleUserDataMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String eventType = json.optString("e", "");
            switch (eventType) {
                case "ACCOUNT_UPDATE":
                    // WebSocket ACCOUNT_UPDATE has a different structure than REST API
                    // Parse it and convert to REST API format for compatibility
                    if (json.has("a")) {
                        JSONObject accountUpdate = json.getJSONObject("a");

                        // Calculate total wallet balance from balances array
                        double totalWalletBalance = 0.0;
                        if (accountUpdate.has("B")) {
                            JSONArray balances = accountUpdate.getJSONArray("B");
                            for (int i = 0; i < balances.length(); i++) {
                                JSONObject balance = balances.getJSONObject(i);
                                String asset = balance.getString("a");
                                if ("USDT".equals(asset)) {
                                    // Use cross wallet balance (cw) which is the available balance
                                    totalWalletBalance = balance.getDouble("cw");
                                    break;
                                }
                            }
                        }

                        // Create a REST API compatible account info object
                        JSONObject restApiFormat = new JSONObject();
                        restApiFormat.put("totalWalletBalance", totalWalletBalance);
                        restApiFormat.put("totalMarginBalance", totalWalletBalance); // Approximation for UI
                                                                                     // compatibility

                        // Update cached account info
                        cachedAccountInfo = restApiFormat;

                        logger.debug("Account update: totalWalletBalance={}", totalWalletBalance);

                        // *** UPDATE POSITIONS CACHE FROM WEBSOCKET ***
                        // Parse positions from ACCOUNT_UPDATE event (field "P")
                        if (accountUpdate.has("P")) {
                            JSONArray wsPositions = accountUpdate.getJSONArray("P");
                            JSONArray restApiPositions = new JSONArray();

                            for (int i = 0; i < wsPositions.length(); i++) {
                                JSONObject wsPos = wsPositions.getJSONObject(i);

                                // Convert WebSocket position format to REST API format
                                JSONObject restPos = new JSONObject();
                                restPos.put("symbol", wsPos.getString("s"));
                                // Parse string values to double for consistency with REST API
                                restPos.put("positionAmt", Double.parseDouble(wsPos.getString("pa")));
                                restPos.put("entryPrice", Double.parseDouble(wsPos.getString("ep")));
                                restPos.put("unRealizedProfit", Double.parseDouble(wsPos.optString("up", "0")));
                                restPos.put("positionSide", wsPos.optString("ps", "BOTH"));

                                restApiPositions.put(restPos);
                            }

                            // Update cached positions
                            cachedPositions = restApiPositions;

                            // Notify position cache listeners (PositionTracker sync)
                            notifyPositionCacheListeners();

                            logger.info("ACCOUNT_UPDATE: Updated positions cache with {} positions",
                                    restApiPositions.length());
                        }
                    }
                    if (accountUpdateListener != null) {
                        accountUpdateListener.accept(json);
                    }
                    break;
                case "ORDER_TRADE_UPDATE":
                    if (orderUpdateListener != null) {
                        orderUpdateListener.accept(json);
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.error("Error handling user data message", e);
        }
    }

    /**
     * Periodically send a keep-alive request for the listen key.
     */
    private void startListenKeyKeepAlive() {
        new Thread(() -> {
            while (userDataWebSocket != null) {
                try {
                    Thread.sleep(30 * 60 * 1000);
                    Request request = new Request.Builder()
                            .url("https://fapi.binance.com/fapi/v1/listenKey")
                            .put(RequestBody.create("", null))
                            .addHeader("X-MBX-APIKEY", apiKey)
                            .build();
                    try (Response response = httpClient.newCall(request).execute()) {
                        if (!response.isSuccessful()) {
                            logger.warn("Listen key keep-alive failed");
                        }
                    }
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    logger.error("Error in listen key keep-alive", e);
                }
            }
        }, "ListenKeyKeepAlive").start();
    }

    /**
     * Start mark price stream for given symbols.
     */
    public void startMarkPriceStream(String... symbols) {
        try {
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
     * Handle mark price stream messages.
     */
    private void handleMarkPriceMessage(String text) {
        try {
            JSONObject json = new JSONObject(text);
            if (json.has("data")) {
                JSONObject data = json.getJSONObject("data");
                String symbol = data.getString("s");
                double markPrice = data.getDouble("p");
                markPriceCache.put(symbol, markPrice);
                logger.debug("Mark price updated: {} = {}", symbol, markPrice);
                if (markPriceUpdateListener != null) {
                    markPriceUpdateListener.accept(data);
                }
            }
        } catch (Exception e) {
            logger.error("Error handling mark price message: {}", text, e);
        }
    }

    /**
     * Get latest mark price for a symbol.
     */
    public double getMarkPrice(String symbol) {
        double price = markPriceCache.getOrDefault(symbol, 0.0);
        if (price == 0.0) {
            logger.warn("Mark price cache MISS for {}, cache size: {}", symbol, markPriceCache.size());
        }
        return price;
    }

    /**
     * Get cached klines for a symbol and interval (avoids REST API call)
     */
    public List<JSONObject> getCachedKlines(String symbol, String interval, int limit) {
        String cacheKey = symbol + "_" + interval;

        // Synchronize on klineCache to ensure thread safety
        synchronized (klineCache) {
            LinkedList<JSONObject> klines = klineCache.get(cacheKey);
            if (klines == null || klines.isEmpty()) {
                // Try fallback to just symbol if interval missing (legacy support)
                if (klineCache.containsKey(symbol)) {
                    klines = klineCache.get(symbol);
                } else {
                    logger.warn("No cached klines for {}", cacheKey);
                    return Collections.emptyList();
                }
            }

            int size = klines.size();
            int fromIndex = Math.max(0, size - limit);
            // Return a defensive copy to avoid ConcurrentModificationException during
            // iteration
            return new ArrayList<>(klines.subList(fromIndex, size));
        }
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
     * Set listener for kline updates (closed candles)
     */
    public void setKlineUpdateListener(Consumer<JSONObject> listener) {
        this.klineUpdateListener = listener;
    }

    /**
     * Set OrderBookService for depth stream processing
     */
    public void setOrderBookService(com.turkninja.engine.OrderBookService orderBookService) {
        this.orderBookService = orderBookService;
    }

    /**
     * Start depth (order book) stream for given symbols
     * Subscribes to @depth20@100ms for real-time market depth updates
     */
    public void startDepthStream(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            logger.warn("No symbols provided for depth stream");
            return;
        }

        // Build stream names: ethusdt@depth20@100ms/btcusdt@depth20@100ms/...
        List<String> streams = symbols.stream()
                .map(s -> s.toLowerCase() + "@depth20@100ms")
                .toList();

        String combinedStream = String.join("/", streams);
        String wsUrl = WS_BASE_URL + "/stream?streams=" + combinedStream;

        logger.info("Starting depth stream for {} symbols: {}", symbols.size(), symbols);

        Request request = new Request.Builder().url(wsUrl).build();

        depthWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.info("✅ Depth WebSocket connected");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    handleDepthMessage(text);
                } catch (Exception e) {
                    logger.error("Error handling depth message", e);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                logger.error("Depth WebSocket error", t);
            }
        });
    }

    /**
     * Handle depth stream messages and update OrderBookService
     */
    private void handleDepthMessage(String text) {
        try {
            JSONObject message = new JSONObject(text);

            if (!message.has("stream") || !message.has("data")) {
                return;
            }

            String stream = message.getString("stream");
            JSONObject data = message.getJSONObject("data");

            // Extract symbol from stream name (e.g., "ethusdt@depth20@100ms" -> "ETHUSDT")
            String symbol = stream.split("@")[0].toUpperCase();

            if (orderBookService != null) {
                // Forward to OrderBookService for processing
                orderBookService.processDepthUpdate(symbol, data);
            }

        } catch (Exception e) {
            logger.error("Error parsing depth message", e);
        }
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
        if (depthWebSocket != null) {
            depthWebSocket.close(1000, "Closing");
            depthWebSocket = null;
        }
        logger.info("WebSocket connections closed");
    }
}
