package com.turkninja.engine;

import com.turkninja.config.Config;
import com.turkninja.infra.FuturesBinanceService;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-timeframe analyzer to confirm 15m signals with higher timeframe trends.
 * Uses configurable higher timeframe (default 4H) EMA50 to determine overall
 * trend direction.
 */
public class MultiTimeframeAnalyzer {
    private static final Logger logger = LoggerFactory.getLogger(MultiTimeframeAnalyzer.class);

    private final FuturesBinanceService futuresService;
    private final int EMA_PERIOD = 50;
    private final String higherTimeframe;

    public enum TrendDirection {
        BULLISH,
        BEARISH,
        NEUTRAL
    }

    public MultiTimeframeAnalyzer(FuturesBinanceService futuresService) {
        this.futuresService = futuresService;
        // Read higher timeframe from config (default: 4h for 15m trading)
        this.higherTimeframe = Config.get("strategy.higher.timeframe", "4h");
        logger.info("MultiTimeframeAnalyzer initialized with higher timeframe: {}", higherTimeframe);
    }

    /**
     * Get higher timeframe trend direction based on EMA50
     */
    public TrendDirection get1HTrend(String symbol) {
        try {
            // Get higher timeframe klines (need EMA_PERIOD + buffer)
            String klinesJson = futuresService.getKlines(symbol, higherTimeframe, EMA_PERIOD + 10);
            JSONArray klines = new JSONArray(klinesJson);

            if (klines.length() < EMA_PERIOD) {
                logger.warn("Not enough {} data for {} (got {})", higherTimeframe, symbol, klines.length());
                return TrendDirection.NEUTRAL;
            }

            // Calculate EMA50
            List<Double> closes = new ArrayList<>();
            for (int i = 0; i < klines.length(); i++) {
                JSONArray kline = klines.getJSONArray(i);
                double close = kline.getDouble(4); // Close price
                closes.add(close);
            }

            double ema50 = calculateEMA(closes, EMA_PERIOD);
            double currentPrice = closes.get(closes.size() - 1);

            // Determine trend with 0.7% buffer (wider for 4H) to avoid noise
            double bufferPercent = higherTimeframe.equals("4h") ? 0.007 : 0.005;
            double upperBand = ema50 * (1 + bufferPercent);
            double lowerBand = ema50 * (1 - bufferPercent);

            if (currentPrice > upperBand) {
                return TrendDirection.BULLISH;
            } else if (currentPrice < lowerBand) {
                return TrendDirection.BEARISH;
            } else {
                return TrendDirection.NEUTRAL;
            }

        } catch (Exception e) {
            logger.error("Error calculating {} trend for {}", higherTimeframe, symbol, e);
            return TrendDirection.NEUTRAL;
        }
    }

    /**
     * Calculate Exponential Moving Average
     */
    private double calculateEMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            return 0;
        }

        // Calculate initial SMA
        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += prices.get(i);
        }
        double ema = sum / period;

        // Calculate multiplier
        double multiplier = 2.0 / (period + 1);

        // Calculate EMA
        for (int i = period; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }

        return ema;
    }

    /**
     * Check if 15m signal aligns with higher timeframe trend
     */
    public boolean isSignalConfirmed(String symbol, String signalType) {
        TrendDirection trend = get1HTrend(symbol);

        if ("BUY".equals(signalType)) {
            boolean confirmed = trend == TrendDirection.BULLISH;
            if (!confirmed) {
                logger.info("⏸️ {} LONG signal filtered - {} trend is {}", symbol, higherTimeframe, trend);
            }
            return confirmed;
        } else if ("SELL".equals(signalType)) {
            boolean confirmed = trend == TrendDirection.BEARISH;
            if (!confirmed) {
                logger.info("⏸️ {} SHORT signal filtered - {} trend is {}", symbol, higherTimeframe, trend);
            }
            return confirmed;
        }

        return false;
    }
}
