package com.turkninja.engine;

import com.turkninja.config.Config;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.model.TrendAnalysis;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import org.ta4j.core.BaseBar;
import org.ta4j.core.Bar;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-Timeframe Analysis Service (Phase 2)
 * Verifies trend direction on higher timeframes (1h, 4h) before allowing entry
 * on 5m
 * Prevents counter-trend trades and improves win rate
 */
public class MultiTimeframeService {

    private static final Logger logger = LoggerFactory.getLogger(MultiTimeframeService.class);

    private final FuturesWebSocketService webSocketService;
    private final IndicatorService indicatorService;

    // Cache: symbol -> trend
    private final Map<String, String> trendCache = new ConcurrentHashMap<>();

    // Detailed trend cache
    private final Map<String, TrendAnalysis> detailedTrendCache = new ConcurrentHashMap<>();

    private long lastUpdateTime = 0;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes cache

    // Configuration
    private final boolean enabled;
    private final String higherTimeframe; // "1h" or "4h"
    private final int emaFast;
    private final int emaSlow;

    public MultiTimeframeService(FuturesWebSocketService webSocketService, IndicatorService indicatorService) {
        this.webSocketService = webSocketService;
        this.indicatorService = indicatorService;

        // Load configuration
        this.enabled = Boolean.parseBoolean(Config.get("strategy.mtf.enabled", "true"));
        this.higherTimeframe = Config.get("strategy.mtf.timeframe", "1h");
        this.emaFast = Integer.parseInt(Config.get("strategy.mtf.ema.fast", "21"));
        this.emaSlow = Integer.parseInt(Config.get("strategy.mtf.ema.slow", "50"));

        logger.info("MultiTimeframeService initialized: enabled={}, timeframe={}, EMA={}/{}",
                enabled, higherTimeframe, emaFast, emaSlow);
    }

    /**
     * Get trend direction for a symbol on higher timeframe
     *
     * @param symbol Trading symbol
     * @return "BULLISH", "BEARISH", or "NEUTRAL"
     */
    public String getTrend(String symbol) {
        if (!enabled) {
            return "NEUTRAL"; // Disabled - allow all trades
        }

        // Check cache
        if (isCacheValid()) {
            String cachedTrend = trendCache.get(symbol);
            if (cachedTrend != null) {
                return cachedTrend;
            }
        }

        // Calculate and cache
        return analyzeTrend(symbol);
    }

    /**
     * Get detailed trend analysis with strength scoring
     * 
     * @param symbol Trading symbol
     * @return TrendAnalysis object with direction and strength (0-100)
     */
    public TrendAnalysis getDetailedTrend(String symbol) {
        if (!enabled) {
            return new TrendAnalysis("NEUTRAL", 0);
        }

        // Check cache
        if (isCacheValid()) {
            TrendAnalysis cached = detailedTrendCache.get(symbol);
            if (cached != null) {
                return cached;
            }
        }

        // Calculate detailed trend
        return analyzeDetailedTrend(symbol);
    }

    /**
     * Analyze trend with strength scoring across multiple timeframes
     */
    private TrendAnalysis analyzeDetailedTrend(String symbol) {
        TrendAnalysis analysis = new TrendAnalysis();

        try {
            int totalStrength = 0;

            // Analyze primary timeframe (1h or 4h)
            String primaryTrend = analyzeTrend(symbol);
            analysis.addTimeframeTrend(higherTimeframe, primaryTrend);

            // Fetch klines for detailed analysis
            List<JSONObject> klines = webSocketService.getCachedKlines(symbol, higherTimeframe, 100);

            if (klines.isEmpty() || klines.size() < Math.max(emaFast, emaSlow) + 5) {
                logger.warn("Insufficient klines for detailed MTF analysis on {}", symbol);
                return new TrendAnalysis("NEUTRAL", 0);
            }

            // Convert to BarSeries
            BarSeries series = convertToBarSeries(symbol, klines);

            // Calculate indicators
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            EMAIndicator emaFastInd = new EMAIndicator(closePrice, emaFast);
            EMAIndicator emaSlowInd = new EMAIndicator(closePrice, emaSlow);
            MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
            EMAIndicator macdSignal = new EMAIndicator(macd, 9);

            int lastIndex = series.getEndIndex();
            Num currentPrice = series.getLastBar().getClosePrice();
            Num emaFastValue = emaFastInd.getValue(lastIndex);
            Num emaSlowValue = emaSlowInd.getValue(lastIndex);
            Num macdValue = macd.getValue(lastIndex);
            Num macdSignalValue = macdSignal.getValue(lastIndex);

            // Calculate strength components

            // 1. Timeframe Alignment (+40 points)
            if (primaryTrend.equals("BULLISH") || primaryTrend.equals("BEARISH")) {
                totalStrength += 40;
                analysis.setAligned(true);
            }

            // 2. EMA Distance (+30 points)
            double emaDistance = Math.abs(emaFastValue.doubleValue() - emaSlowValue.doubleValue()) /
                    currentPrice.doubleValue() * 100;
            if (emaDistance > 0.5) { // EMAs separated by >0.5%
                totalStrength += Math.min(30, (int) (emaDistance * 15));
            }

            // 3. MACD Strength (+20 points)
            double macdDiff = Math.abs(macdValue.doubleValue() - macdSignalValue.doubleValue());
            if (macdDiff > 0.001) {
                totalStrength += Math.min(20, (int) (macdDiff * 1000));
            }

            // 4. Volume Confirmation (+10 points)
            double avgVolume = 0;
            int volumeCount = Math.min(20, series.getBarCount());
            for (int i = lastIndex - volumeCount + 1; i <= lastIndex - 1; i++) {
                avgVolume += series.getBar(i).getVolume().doubleValue();
            }
            avgVolume /= (volumeCount - 1);

            double currentVolume = series.getLastBar().getVolume().doubleValue();
            if (currentVolume > avgVolume * 1.2) { // 20% above average
                totalStrength += 10;
            }

            // Determine final direction
            analysis.setDirection(primaryTrend);
            analysis.setStrength(totalStrength);

            // Cache result
            detailedTrendCache.put(symbol, analysis);
            lastUpdateTime = System.currentTimeMillis();

            logger.debug("📊 Detailed MTF {}: {} (strength={})",
                    symbol, analysis.getDirection(), analysis.getStrength());

            return analysis;

        } catch (Exception e) {
            logger.error("Error in detailed MTF analysis for {}: {}", symbol, e.getMessage());
            return new TrendAnalysis("NEUTRAL", 0);
        }
    }

    /**
     * Analyze trend on higher timeframe
     */
    private String analyzeTrend(String symbol) {
        try {
            // Fetch cached klines from WebSocket
            List<JSONObject> klines = webSocketService.getCachedKlines(symbol, higherTimeframe, 100);

            if (klines.isEmpty() || klines.size() < Math.max(emaFast, emaSlow) + 5) {
                logger.warn("Insufficient klines for MTF analysis on {} ({}): {} candles",
                        symbol, higherTimeframe, klines.size());
                return "NEUTRAL"; // Not enough data - allow trade
            }

            // Convert to BarSeries
            BarSeries series = convertToBarSeries(symbol, klines);

            // Calculate indicators
            ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
            EMAIndicator emaFastInd = new EMAIndicator(closePrice, emaFast);
            EMAIndicator emaSlowInd = new EMAIndicator(closePrice, emaSlow);

            // MACD for confirmation
            MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
            EMAIndicator macdSignal = new EMAIndicator(macd, 9);

            // Get latest values
            int lastIndex = series.getEndIndex();
            Num currentPrice = series.getLastBar().getClosePrice();
            Num emaFastValue = emaFastInd.getValue(lastIndex);
            Num emaSlowValue = emaSlowInd.getValue(lastIndex);
            Num macdValue = macd.getValue(lastIndex);
            Num macdSignalValue = macdSignal.getValue(lastIndex);

            // Trend Logic (3 conditions)
            String trend = "NEUTRAL";

            // Bullish: Price > EMA Fast > EMA Slow AND MACD > Signal
            if (currentPrice.isGreaterThan(emaFastValue) &&
                    emaFastValue.isGreaterThan(emaSlowValue) &&
                    macdValue.isGreaterThan(macdSignalValue)) {
                trend = "BULLISH";
            }
            // Bearish: Price < EMA Fast < EMA Slow AND MACD < Signal
            else if (currentPrice.isLessThan(emaFastValue) &&
                    emaFastValue.isLessThan(emaSlowValue) &&
                    macdValue.isLessThan(macdSignalValue)) {
                trend = "BEARISH";
            }

            // Cache result
            trendCache.put(symbol, trend);
            lastUpdateTime = System.currentTimeMillis();

            logger.debug("📊 MTF {} ({}): {} | Price={:.2f}, EMA{:.0f}={:.2f}, EMA{:.0f}={:.2f}, MACD={:.4f}/{:.4f}",
                    symbol, higherTimeframe, trend,
                    currentPrice.doubleValue(),
                    (double) emaFast, emaFastValue.doubleValue(),
                    (double) emaSlow, emaSlowValue.doubleValue(),
                    macdValue.doubleValue(), macdSignalValue.doubleValue());

            return trend;

        } catch (Exception e) {
            logger.error("Error analyzing MTF trend for {}: {}", symbol, e.getMessage());
            return "NEUTRAL"; // Error - allow trade (fail open)
        }
    }

    /**
     * Convert cached klines to BarSeries
     */
    private BarSeries convertToBarSeries(String symbol, List<JSONObject> klines) {
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(symbol + "_" + higherTimeframe)
                .build();

        for (JSONObject kline : klines) {
            long openTime = kline.getLong("t");
            double open = kline.getDouble("o");
            double high = kline.getDouble("h");
            double low = kline.getDouble("l");
            double close = kline.getDouble("c");
            double volume = kline.getDouble("v");

            // Add bar directly to series (compatibility with ta4j)
            // Assuming 1h or 4h timeframe based on config, but we can default to 1h for
            // duration
            Duration duration = higherTimeframe.equals("4h") ? Duration.ofHours(4) : Duration.ofHours(1);

            Instant beginTime = Instant.ofEpochMilli(openTime);
            Instant endTime = beginTime.plus(duration);

            Bar bar = new BaseBar(null,
                    endTime,
                    beginTime,
                    DecimalNum.valueOf(open),
                    DecimalNum.valueOf(high),
                    DecimalNum.valueOf(low),
                    DecimalNum.valueOf(close),
                    DecimalNum.valueOf(volume),
                    DecimalNum.valueOf(0), // amount
                    0L); // trades
            series.addBar(bar);
        }

        return series;
    }

    /**
     * Check if cache is still valid
     */
    private boolean isCacheValid() {
        return System.currentTimeMillis() - lastUpdateTime < CACHE_TTL_MS;
    }

    /**
     * Clear trend cache (force recalculation)
     */
    public void clearCache() {
        trendCache.clear();
        lastUpdateTime = 0;
        logger.info("MTF trend cache cleared");
    }

    /**
     * Check if MTF filter allows a LONG trade
     * Enhanced: Only block strong counter-trends (strength > 60)
     * 
     * @param symbol Trading symbol
     * @return true if allowed, false if blocked
     */
    public boolean allowLong(String symbol) {
        if (!enabled) {
            return true;
        }

        // Get detailed trend analysis
        TrendAnalysis analysis = getDetailedTrend(symbol);

        // Only block STRONG bearish trends
        if (analysis.getDirection().equals("BEARISH") && analysis.getStrength() > 60) {
            logger.info("⏸️ {} LONG filtered by MTF - {} trend is BEARISH with strength {}",
                    symbol, higherTimeframe, analysis.getStrength());
            return false;
        }

        // Allow NEUTRAL or weak BEARISH trends
        return true;
    }

    /**
     * Check if MTF filter allows a SHORT trade
     * Enhanced: Only block strong counter-trends (strength > 60)
     * 
     * @param symbol Trading symbol
     * @return true if allowed, false if blocked
     */
    public boolean allowShort(String symbol) {
        if (!enabled) {
            return true;
        }

        // Get detailed trend analysis
        TrendAnalysis analysis = getDetailedTrend(symbol);

        // Only block STRONG bullish trends
        if (analysis.getDirection().equals("BULLISH") && analysis.getStrength() > 60) {
            logger.info("⏸️ {} SHORT filtered by MTF - {} trend is BULLISH with strength {}",
                    symbol, higherTimeframe, analysis.getStrength());
            return false;
        }

        // Allow NEUTRAL or weak BULLISH trends
        return true;
    }

    /**
     * Check if service is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
}
