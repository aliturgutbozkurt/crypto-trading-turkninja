package com.turkninja.engine;

import com.turkninja.config.Config;
import com.turkninja.infra.FuturesWebSocketService;
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
     *
     * @param symbol Trading symbol
     * @return true if allowed, false if blocked
     */
    public boolean allowLong(String symbol) {
        String trend = getTrend(symbol);
        // Allow LONG only if trend is BULLISH or NEUTRAL
        boolean allowed = !trend.equals("BEARISH");

        if (!allowed) {
            logger.info("⏸️ {} LONG filtered by MTF - {} trend is {}", symbol, higherTimeframe, trend);
        }

        return allowed;
    }

    /**
     * Check if MTF filter allows a SHORT trade
     *
     * @param symbol Trading symbol
     * @return true if allowed, false if blocked
     */
    public boolean allowShort(String symbol) {
        String trend = getTrend(symbol);
        // Allow SHORT only if trend is BEARISH or NEUTRAL
        boolean allowed = !trend.equals("BULLISH");

        if (!allowed) {
            logger.info("⏸️ {} SHORT filtered by MTF - {} trend is {}", symbol, higherTimeframe, trend);
        }

        return allowed;
    }

    /**
     * Check if service is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
}
