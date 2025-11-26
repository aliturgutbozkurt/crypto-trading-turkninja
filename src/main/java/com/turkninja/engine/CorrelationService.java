package com.turkninja.engine;

import com.turkninja.infra.FuturesBinanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for calculating and managing correlation between trading symbols
 * to prevent over-exposure to correlated assets.
 */
@Service
public class CorrelationService {

    private static final Logger logger = LoggerFactory.getLogger(CorrelationService.class);

    private final FuturesBinanceService binanceService;

    // Cache: symbol1 -> symbol2 -> correlation coefficient
    private final Map<String, Map<String, Double>> correlationCache = new ConcurrentHashMap<>();

    // Last cache update timestamp
    private long lastUpdateTime = 0;

    // Cache TTL in milliseconds (1 hour)
    private static final long CACHE_TTL_MS = 60 * 60 * 1000;

    // Number of hourly candles to fetch for correlation (24 hours)
    private static final int CORRELATION_PERIOD = 24;

    public CorrelationService(FuturesBinanceService binanceService) {
        this.binanceService = binanceService;
    }

    /**
     * Get correlation coefficient between two symbols
     * Uses cached value if available and fresh, otherwise calculates
     * 
     * @param symbol1 First symbol
     * @param symbol2 Second symbol
     * @return Correlation coefficient [-1.0, 1.0]
     */
    public double getCorrelation(String symbol1, String symbol2) {
        // Same symbol = perfect correlation
        if (symbol1.equals(symbol2)) {
            return 1.0;
        }

        // Check cache
        if (isCacheValid()) {
            Double cachedCorr = getCachedCorrelation(symbol1, symbol2);
            if (cachedCorr != null) {
                return cachedCorr;
            }
        }

        // Calculate and cache
        return calculateAndCacheCorrelation(symbol1, symbol2);
    }

    /**
     * Get average correlation of a symbol with a list of other symbols
     * 
     * @param symbol       Symbol to check
     * @param otherSymbols List of symbols to correlate with
     * @return Average correlation coefficient
     */
    public double getAverageCorrelation(String symbol, List<String> otherSymbols) {
        if (otherSymbols == null || otherSymbols.isEmpty()) {
            return 0.0;
        }

        double sum = 0.0;
        int count = 0;

        for (String other : otherSymbols) {
            if (!symbol.equals(other)) {
                sum += Math.abs(getCorrelation(symbol, other)); // Use absolute value
                count++;
            }
        }

        return count > 0 ? sum / count : 0.0;
    }

    /**
     * Calculate Pearson correlation coefficient between two price series
     * 
     * @param returns1 Price returns for symbol 1
     * @param returns2 Price returns for symbol 2
     * @return Correlation coefficient [-1.0, 1.0]
     */
    private double calculatePearsonCorrelation(double[] returns1, double[] returns2) {
        if (returns1.length != returns2.length || returns1.length == 0) {
            logger.warn("Invalid returns arrays for correlation calculation");
            return 0.0;
        }

        int n = returns1.length;

        // Calculate means
        double mean1 = 0.0;
        double mean2 = 0.0;
        for (int i = 0; i < n; i++) {
            mean1 += returns1[i];
            mean2 += returns2[i];
        }
        mean1 /= n;
        mean2 /= n;

        // Calculate correlation components
        double numerator = 0.0;
        double sumSq1 = 0.0;
        double sumSq2 = 0.0;

        for (int i = 0; i < n; i++) {
            double diff1 = returns1[i] - mean1;
            double diff2 = returns2[i] - mean2;
            numerator += diff1 * diff2;
            sumSq1 += diff1 * diff1;
            sumSq2 += diff2 * diff2;
        }

        // Avoid division by zero
        if (sumSq1 == 0 || sumSq2 == 0) {
            return 0.0;
        }

        return numerator / Math.sqrt(sumSq1 * sumSq2);
    }

    /**
     * Calculate correlation and store in cache
     */
    private double calculateAndCacheCorrelation(String symbol1, String symbol2) {
        try {
            // Fetch historical data (24h, 1h interval)
            List<Double> prices1 = fetchHistoricalPrices(symbol1);
            List<Double> prices2 = fetchHistoricalPrices(symbol2);

            if (prices1.size() < 2 || prices2.size() < 2) {
                logger.warn("Insufficient price data for {} or {}", symbol1, symbol2);
                return 0.0;
            }

            // Calculate returns
            double[] returns1 = calculateReturns(prices1);
            double[] returns2 = calculateReturns(prices2);

            // Calculate correlation
            double correlation = calculatePearsonCorrelation(returns1, returns2);

            // Cache the result (both directions)
            cacheCorrelation(symbol1, symbol2, correlation);

            logger.debug("Calculated correlation: {} ↔ {} = {:.3f}",
                    symbol1, symbol2, correlation);

            return correlation;

        } catch (Exception e) {
            logger.error("Error calculating correlation for {} and {}", symbol1, symbol2, e);
            return 0.0;
        }
    }

    /**
     * Fetch 24h historical prices for a symbol (1h candles)
     */
    private List<Double> fetchHistoricalPrices(String symbol) {
        try {
            // Fetch kline data from Binance
            String klineData = binanceService.getKlines(symbol, "1h", CORRELATION_PERIOD);

            // Parse JSON and extract close prices
            List<Double> prices = new ArrayList<>();

            // Simple JSON parsing (klines are arrays of arrays)
            String[] candles = klineData.substring(1, klineData.length() - 1).split("\\],\\[");

            for (String candle : candles) {
                String[] fields = candle.replaceAll("[\\[\\]\"]", "").split(",");
                if (fields.length > 4) {
                    double closePrice = Double.parseDouble(fields[4]); // Close price is 5th field
                    prices.add(closePrice);
                }
            }

            return prices;

        } catch (Exception e) {
            logger.error("Error fetching historical prices for {}", symbol, e);
            return new ArrayList<>();
        }
    }

    /**
     * Calculate returns from prices
     */
    private double[] calculateReturns(List<Double> prices) {
        double[] returns = new double[prices.size() - 1];

        for (int i = 1; i < prices.size(); i++) {
            returns[i - 1] = (prices.get(i) - prices.get(i - 1)) / prices.get(i - 1);
        }

        return returns;
    }

    /**
     * Check if correlation cache is still valid
     */
    private boolean isCacheValid() {
        return System.currentTimeMillis() - lastUpdateTime < CACHE_TTL_MS;
    }

    /**
     * Get cached correlation value
     */
    private Double getCachedCorrelation(String symbol1, String symbol2) {
        Map<String, Double> symbol1Corr = correlationCache.get(symbol1);
        if (symbol1Corr != null) {
            return symbol1Corr.get(symbol2);
        }
        return null;
    }

    /**
     * Store correlation in cache (both directions)
     */
    private void cacheCorrelation(String symbol1, String symbol2, double correlation) {
        correlationCache.computeIfAbsent(symbol1, k -> new ConcurrentHashMap<>())
                .put(symbol2, correlation);
        correlationCache.computeIfAbsent(symbol2, k -> new ConcurrentHashMap<>())
                .put(symbol1, correlation);

        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Clear correlation cache (force recalculation)
     */
    public void clearCache() {
        correlationCache.clear();
        lastUpdateTime = 0;
        logger.info("Correlation cache cleared");
    }
}
