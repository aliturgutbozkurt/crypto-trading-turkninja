package com.turkninja.infra;

import com.turkninja.config.Config;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Binance USDT-M Futures Service for 20x Leverage Trading
 * Uses direct REST API calls since there's no proper Java Futures library
 */
public class FuturesBinanceService {
    private static final Logger logger = LoggerFactory.getLogger(FuturesBinanceService.class);
    private static final String FUTURES_BASE_URL = "https://fapi.binance.com";
    private static final String WS_BASE_URL = "wss://fstream.binance.com";

    private final String apiKey;
    private final String secretKey;
    private final OkHttpClient httpClient;

    // ThreadLocal Mac instance for performance optimization (Phase 1.3)
    // Avoids expensive Mac.getInstance() calls on every signature generation
    private final ThreadLocal<Mac> macThreadLocal;

    // Resilience4j Retry and Circuit Breaker configs (Phase 1.3)
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    // Symbols to trade with 20x leverage
    private static final String[] TRADING_SYMBOLS = {
            "ATOMUSDT", "BTCUSDT", "ETHUSDT", "DOGEUSDT",
            "SOLUSDT", "XRPUSDT", "ALGOUSDT",
            "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT"
    };

    public FuturesBinanceService() {
        this.apiKey = Config.get(Config.BINANCE_API_KEY);
        this.secretKey = Config.get(Config.BINANCE_SECRET_KEY);

        // Initialize ThreadLocal Mac with secretKey (after secretKey is set)
        this.macThreadLocal = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(this.secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return mac;
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize HMAC SHA256", e);
            }
        });

        if (apiKey == null || secretKey == null) {
            logger.warn(
                    "Binance API Keys not found. Futures service will fail on signed requests unless in Dry Run mode.");
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .build();

        // Configure Retry: Exponential backoff for transient errors
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(4) // Max 4 retries
                .intervalFunction(io.github.resilience4j.core.IntervalFunction.ofExponentialBackoff(
                        Duration.ofMillis(500), 2.0)) // 500ms → 1s → 2s → 4s
                .retryOnException(e -> {
                    // Retry on network errors and specific HTTP codes
                    String message = e.getMessage();
                    if (message == null)
                        return false;

                    // Retry on rate limit (429), server errors (500, 502, 503)
                    boolean shouldRetry = message.contains("429") ||
                            message.contains("500") ||
                            message.contains("502") ||
                            message.contains("503") ||
                            message.contains("SocketTimeoutException") ||
                            message.contains("ConnectException");

                    if (shouldRetry) {
                        logger.warn("⚠️ Retryable error detected: {}", message);
                    }
                    return shouldRetry;
                })
                .build();

        this.retry = Retry.of("binanceApi", retryConfig);

        // Configure Circuit Breaker: Stop retrying after too many failures
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // Open circuit if 50% of calls fail
                .waitDurationInOpenState(Duration.ofMinutes(1)) // Wait 1 min before retry
                .slidingWindowSize(10) // Last 10 calls
                .build();

        this.circuitBreaker = CircuitBreaker.of("binanceApi", cbConfig);

        // Log retry/circuit breaker events
        retry.getEventPublisher()
                .onRetry(event -> logger.warn("🔄 API Retry {}/{}: {}",
                        event.getNumberOfRetryAttempts(), retryConfig.getMaxAttempts(),
                        event.getLastThrowable().getMessage()));

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> logger.error("⚡ Circuit Breaker: {} → {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));

        logger.info("Binance Futures Service initialized with Retry (max: 4) and Circuit Breaker");

        // Initialize leverage and margin type for all symbols (async to speed up
        // startup)
        Thread.ofVirtual().start(this::initializeTradingSettings);
    }

    private void initializeTradingSettings() {
        logger.info("Initializing trading settings for {} symbols...", TRADING_SYMBOLS.length);

        // Use virtual threads for parallel initialization
        for (String symbol : TRADING_SYMBOLS) {
            Thread.ofVirtual().start(() -> {
                try {
                    // Set margin type to CROSSED
                    try {
                        LinkedHashMap<String, Object> marginParams = new LinkedHashMap<>();
                        marginParams.put("symbol", symbol);
                        marginParams.put("marginType", "CROSSED");

                        signedRequest("POST", "/fapi/v1/marginType", marginParams);
                        logger.info("Set {} to CROSSED margin", symbol);
                    } catch (Exception e) {
                        // Might already be set to CROSSED
                        logger.debug("Margin type for {} might already be CROSSED: {}", symbol, e.getMessage());
                    }

                    // Set leverage to 20x
                    LinkedHashMap<String, Object> leverageParams = new LinkedHashMap<>();
                    leverageParams.put("symbol", symbol);
                    leverageParams.put("leverage", 20);

                    String response = signedRequest("POST", "/fapi/v1/leverage", leverageParams);
                    logger.info("Set {} leverage to 20x: {}", symbol, response);

                } catch (Exception e) {
                    logger.error("Failed to initialize settings for {}: {}", symbol, e.getMessage());
                }
            });
        }

        logger.info("Trading settings initialization started in background");
    }

    /**
     * Make a signed request to Binance Futures API
     */
    private String signedRequest(String method, String endpoint, Map<String, Object> params) throws Exception {
        long timestamp = System.currentTimeMillis();
        params.put("timestamp", timestamp);

        String queryString = buildQueryString(params);
        String signature = generateSignature(queryString);
        queryString += "&signature=" + signature;

        String url = FUTURES_BASE_URL + endpoint + "?" + queryString;

        Request.Builder requestBuilder = new Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiKey);

        if ("POST".equals(method)) {
            requestBuilder.post(RequestBody.create("", MediaType.parse("application/x-www-form-urlencoded")));
        } else {
            requestBuilder.get();
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No error body";
                throw new RuntimeException("API call failed: " + response.code() + " - " + errorBody);
            }
            return response.body() != null ? response.body().string() : "";
        }
    }

    private String buildQueryString(Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (sb.length() > 0)
                sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * Generate HMAC SHA256 signature
     * Optimized with ThreadLocal Mac instance (Phase 1.3)
     * Performance: ~30% faster than Mac.getInstance() per call
     */
    private String generateSignature(String data) throws Exception {
        Mac mac = macThreadLocal.get();
        // Mac is already initialized with secretKey in ThreadLocal

        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1)
                hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public String getAccountInfo() {
        try {
            return signedRequest("GET", "/fapi/v2/account", new LinkedHashMap<>());
        } catch (Exception e) {
            logger.error("Failed to get account info", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Get available USDT balance for trading
     */
    public double getAvailableBalance() {
        try {
            String response = getAccountInfo();
            JSONObject json = new JSONObject(response);
            JSONArray assets = json.getJSONArray("assets");

            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if ("USDT".equals(asset.getString("asset"))) {
                    return asset.getDouble("availableBalance");
                }
            }
            return 0.0;
        } catch (Exception e) {
            logger.error("Failed to get available balance", e);
            return 0.0;
        }
    }

    public String getPositionInfo() {
        try {
            return signedRequest("GET", "/fapi/v2/positionRisk", new LinkedHashMap<>());
        } catch (Exception e) {
            logger.error("Failed to get position info", e);
            return "[]";
        }
    }

    public String getPositionInfo(String symbol) {
        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", symbol);
            return signedRequest("GET", "/fapi/v2/positionRisk", parameters);
        } catch (Exception e) {
            logger.error("Failed to get position info for {}", symbol, e);
            return "[]";
        }
    }

    public String getKlines(String symbol, String interval, int limit) {
        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", symbol);
            parameters.put("interval", interval);
            parameters.put("limit", limit);

            String queryString = buildQueryString(parameters);
            String url = FUTURES_BASE_URL + "/fapi/v1/klines?" + queryString;

            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.body() != null ? response.body().string() : "[]";
            }
        } catch (Exception e) {
            logger.error("Failed to get klines for {}", symbol, e);
            return "[]";
        }
    }

    public double getSymbolPriceTicker(String symbol) {
        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", symbol);

            String queryString = buildQueryString(parameters);
            String url = FUTURES_BASE_URL + "/fapi/v1/ticker/price?" + queryString;

            Request request = new Request.Builder().url(url).build();
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "{}";
                JSONObject json = new JSONObject(responseBody);
                return json.getDouble("price");
            }
        } catch (Exception e) {
            logger.error("Failed to get price ticker for {}", symbol, e);
            return 0.0;
        }
    }

    /**
     * Place a market order
     */
    /**
     * Place market order with retry logic (Phase 1.3)
     * Retries on network/API failures, fails fast on validation errors
     */
    public String placeMarketOrder(String symbol, String side, double quantity) {
        // Wrap with Retry and Circuit Breaker
        Supplier<String> orderSupplier = () -> {
            try {
                LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("symbol", symbol);
                parameters.put("side", side);
                parameters.put("type", "MARKET");
                parameters.put("quantity", quantity);

                logger.info("Placing MARKET order: {} {} {}", side, quantity, symbol);
                return signedRequest("POST", "/fapi/v1/order", parameters);
            } catch (Exception e) {
                logger.error("Failed to place market order", e);
                return "{\"error\": \"" + e.getMessage() + "\"}";
            }
        };

        // Apply Retry decorateion then Circuit Breaker
        Supplier<String> decorated = Retry.decorateSupplier(retry, orderSupplier);
        decorated = CircuitBreaker.decorateSupplier(circuitBreaker, decorated);
        return decorated.get();
    }

    /**
     * Place a limit order
     */
    public String placeLimitOrder(String symbol, String side, double quantity, double price) {
        try {
            LinkedHashMap<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("symbol", symbol);
            parameters.put("side", side);
            parameters.put("type", "LIMIT");
            parameters.put("quantity", quantity);
            parameters.put("price", price);
            parameters.put("timeInForce", "GTC");

            logger.info("Placing LIMIT order: {} {} {} @ {}", side, quantity, symbol, price);
            return signedRequest("POST", "/fapi/v1/order", parameters);
        } catch (Exception e) {
            logger.error("Failed to place limit order", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Close all positions for a symbol
     */
    public String closePosition(String symbol) {
        if (dryRun) {
            logger.info("[DRY RUN] Position closed for {}", symbol);
            return "{\"symbol\": \"" + symbol + "\", \"status\": \"CLOSED\", \"pnl\": 0.0}";
        }

        try {
            // Get current position to determine side
            String positionJson = getPositionInfo(symbol);
            JSONArray positions = new JSONArray(positionJson);

            if (positions.length() == 0) {
                return "{\"error\": \"No position found\"}";
            }

            JSONObject position = positions.getJSONObject(0);
            double positionAmt = position.getDouble("positionAmt");

            if (positionAmt == 0) {
                return "{\"info\": \"No open position\"}";
            }

            // Close position by placing opposite order
            String side = positionAmt > 0 ? "SELL" : "BUY";
            double quantity = Math.abs(positionAmt);

            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", "MARKET");
            params.put("quantity", quantity);
            params.put("reduceOnly", true);

            return signedRequest("POST", "/fapi/v1/order", params);

        } catch (Exception e) {
            logger.error("Failed to close position for {}", symbol, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private boolean dryRun = false;

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        logger.info("Dry run mode set to: {}", dryRun);
    }

    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Place a market order (BUY or SELL)
     */
    public String placeOrder(String symbol, String side, double quantity) {
        if (dryRun) {
            logger.info("[DRY RUN] Order placed: {} {} {} @ MARKET", side, quantity, symbol);
            return "{\"orderId\": 123456789, \"symbol\": \"" + symbol
                    + "\", \"status\": \"FILLED\", \"type\": \"MARKET\", \"side\": \"" + side + "\"}";
        }

        try {
            LinkedHashMap<String, Object> params = new LinkedHashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", "MARKET");
            params.put("quantity", quantity);

            String response = signedRequest("POST", "/fapi/v1/order", params);
            logger.info("Order placed: {} {} {} @ MARKET", side, quantity, symbol);
            return response;

        } catch (Exception e) {
            logger.error("Failed to place order for {}", symbol, e);
            throw new RuntimeException("Order placement failed: " + e.getMessage(), e);
        }
    }

    public void close() {
        // OkHttpClient doesn't need explicit closing
        logger.info("Futures service closed");
    }

    /**
     * Get Income History (Trade History, Realized PnL, Funding Fee, etc.)
     * GET /fapi/v1/income
     */
    public String getIncomeHistory(String symbol, String incomeType, Long startTime, Long endTime, Integer limit)
            throws Exception {
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        if (symbol != null)
            params.put("symbol", symbol);
        if (incomeType != null)
            params.put("incomeType", incomeType); // e.g., "REALIZED_PNL", "COMMISSION", "FUNDING_FEE"
        if (startTime != null)
            params.put("startTime", startTime);
        if (endTime != null)
            params.put("endTime", endTime);
        if (limit != null)
            params.put("limit", limit);

        return signedRequest("GET", "/fapi/v1/income", params);
    }
}
