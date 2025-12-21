package com.turkninja.engine;

import com.turkninja.config.Config;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ML Signal Validator
 * Validates trading signals using an external ML microservice.
 * Falls back to allowing trades if service is unavailable.
 */
public class MLSignalValidator {

    private static final Logger logger = LoggerFactory.getLogger(MLSignalValidator.class);

    private final HttpClient httpClient;
    private final boolean enabled;
    private final String serviceUrl;
    private final double minProbability;
    private final int timeoutMs;

    public MLSignalValidator() {
        this.enabled = Config.getBoolean("ml.signal.validator.enabled", false);
        this.serviceUrl = Config.get("ml.signal.validator.url", "http://localhost:8000/predict");
        this.minProbability = Config.getDouble("ml.signal.validator.min.probability", 0.6);
        this.timeoutMs = Config.getInt("ml.signal.validator.timeout.ms", 100);

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        logger.info("✅ MLSignalValidator initialized: enabled={}, url={}, minProbability={:.0f}%, timeout={}ms",
                enabled, serviceUrl, minProbability * 100, timeoutMs);
    }

    /**
     * Validate a trading signal
     * 
     * @param symbol       Trading symbol (e.g., "BTCUSDT")
     * @param side         Trade direction ("BUY" or "SELL")
     * @param rsi          RSI value (0-100)
     * @param macd         MACD value
     * @param macdSignal   MACD signal line
     * @param emaAlignment EMA alignment as %
     * @param atrPercent   ATR as % of price
     * @param volumeRatio  Volume ratio (current/avg)
     * @param cvd          Cumulative Volume Delta
     * @param adx          ADX trend strength
     * @param price        Current price
     * @return true if signal is validated (should trade), false otherwise
     */
    public boolean validateSignal(String symbol, String side, double rsi, double macd, double macdSignal,
            double emaAlignment, double atrPercent, double volumeRatio, double cvd, double adx, double price) {

        if (!enabled) {
            // ML validation disabled - allow all trades
            return true;
        }

        try {
            // Build request body
            JSONObject requestBody = new JSONObject();
            requestBody.put("symbol", symbol);
            requestBody.put("side", side);
            requestBody.put("rsi", rsi);
            requestBody.put("macd", macd);
            requestBody.put("macd_signal", macdSignal);
            requestBody.put("ema_alignment", emaAlignment);
            requestBody.put("atr_percent", atrPercent);
            requestBody.put("volume_ratio", volumeRatio);
            requestBody.put("cvd", cvd);
            requestBody.put("adx", adx);
            requestBody.put("price", price);

            // Send async request with timeout
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serviceUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .build();

            CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(request,
                    HttpResponse.BodyHandlers.ofString());

            // Wait with timeout
            HttpResponse<String> response = future.get(timeoutMs, TimeUnit.MILLISECONDS);

            if (response.statusCode() == 200) {
                JSONObject result = new JSONObject(response.body());

                double probability = result.getDouble("probability");
                boolean recommended = result.getBoolean("recommended");
                String confidence = result.getString("confidence");

                if (recommended) {
                    logger.info("✅ ML Signal VALIDATED: {} {} - {:.1f}% probability ({})",
                            symbol, side, probability * 100, confidence);
                } else {
                    logger.warn("❌ ML Signal REJECTED: {} {} - {:.1f}% probability ({})",
                            symbol, side, probability * 100, confidence);
                }

                return recommended;

            } else {
                logger.warn("⚠️ ML service returned status {}, falling back to allow trade", response.statusCode());
                return true;
            }

        } catch (java.util.concurrent.TimeoutException e) {
            logger.warn("⚠️ ML validation timeout ({}ms), falling back to allow trade", timeoutMs);
            return true;

        } catch (Exception e) {
            logger.warn("⚠️ ML validation error: {}, falling back to allow trade", e.getMessage());
            return true;
        }
    }

    /**
     * Check if ML validation is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get minimum probability threshold
     */
    public double getMinProbability() {
        return minProbability;
    }

    /**
     * Health check for ML service
     */
    public boolean isServiceHealthy() {
        if (!enabled)
            return true;

        try {
            String healthUrl = serviceUrl.replace("/predict", "/health");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(Duration.ofMillis(timeoutMs * 2))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;

        } catch (Exception e) {
            logger.warn("ML service health check failed: {}", e.getMessage());
            return false;
        }
    }
}
