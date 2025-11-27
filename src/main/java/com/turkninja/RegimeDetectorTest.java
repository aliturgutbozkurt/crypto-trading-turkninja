package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.IndicatorService;
import com.turkninja.engine.MarketRegime;
import com.turkninja.engine.MarketRegimeDetector;
import com.turkninja.infra.FuturesBinanceService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Test Market Regime Detection
 * 
 * Runs regime detector on recent data for all symbols to verify classification
 * accuracy
 */
public class RegimeDetectorTest {

    private static final Logger logger = LoggerFactory.getLogger(RegimeDetectorTest.class);

    private static final List<String> TEST_SYMBOLS = Arrays.asList(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "ATOMUSDT",
            "DOGEUSDT", "ALGOUSDT", "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT");

    public static void main(String[] args) {
        logger.info("╔══════════════════════════════════════════════════════════╗");
        logger.info("║          MARKET REGIME DETECTION TEST                    ║");
        logger.info("╚══════════════════════════════════════════════════════════╝");
        logger.info("");

        try {
            // Disable batch mode for testing
            Config.setProperty("strategy.batch.enabled", "false");

            // Initialize services
            FuturesBinanceService binanceService = new FuturesBinanceService(true);
            IndicatorService indicatorService = new IndicatorService();
            MarketRegimeDetector regimeDetector = new MarketRegimeDetector(indicatorService);

            logger.info("Testing regime detection on {} symbols...\n", TEST_SYMBOLS.size());

            // Test each symbol
            for (String symbol : TEST_SYMBOLS) {
                testSymbol(symbol, binanceService, indicatorService, regimeDetector);
            }

            logger.info("\n✅ Regime detection test completed!");

        } catch (Exception e) {
            logger.error("Test failed", e);
            System.exit(1);
        }
    }

    private static void testSymbol(String symbol, FuturesBinanceService binanceService,
            IndicatorService indicatorService, MarketRegimeDetector regimeDetector) {
        try {
            // Fetch recent klines (last 100 5m candles)
            String klinesJson = binanceService.getKlines(symbol, "5m", 100);
            BarSeries series = convertToBarSeries(symbol, klinesJson);

            if (series.getBarCount() < 50) {
                logger.warn("⚠️ Insufficient data for {}", symbol);
                return;
            }

            // Calculate indicators
            Map<String, Double> indicators = indicatorService.calculateIndicators(series);

            // Detect regime
            MarketRegime regime = regimeDetector.detectRegime(symbol, series, indicators);

            // Get metrics
            double currentPrice = series.getLastBar().getClosePrice().doubleValue();
            double adx = indicators.getOrDefault("ADX", 0.0);
            double atrPercent = indicators.getOrDefault("ATR_PERCENT", 0.0);
            double ema50 = indicators.getOrDefault("EMA_50", currentPrice);
            double emaSlope = indicatorService.calculateEMASlope(series, 50, 10);
            double trendStrength = regimeDetector.getTrendStrength(regime, adx);
            double positionMultiplier = regimeDetector.getPositionSizeMultiplier(regime);

            // Display results
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.printf("%-12s: %s\n", symbol, regime);
            System.out.println("─────────────────────────────────────────────");
            System.out.printf("Price:     $%.2f  %s EMA50 ($%.2f)\n",
                    currentPrice, currentPrice > ema50 ? ">" : "<", ema50);
            System.out.printf("ADX:       %.1f  (%.0f%% strength)\n", adx, trendStrength);
            System.out.printf("Volatility: %.2f%%\n", atrPercent);
            System.out.printf("EMA Slope:  %.3f%%\n", emaSlope);
            System.out.printf("Strategy:   %s\n", getStrategyRecommendation(regime));
            System.out.printf("Position:   %.0f%% of normal size\n", positionMultiplier * 100);
            System.out.println();

        } catch (Exception e) {
            logger.error("Error testing {}", symbol, e);
        }
    }

    private static String getStrategyRecommendation(MarketRegime regime) {
        if (regime.isTrending()) {
            return "Trend-Following " + (regime.isStrong() ? "(Aggressive)" : "(Cautious)");
        } else if (regime.isRanging()) {
            return "Mean Reversion";
        } else {
            return "Stay Flat / Manage Existing";
        }
    }

    private static BarSeries convertToBarSeries(String symbol, String klinesJson) {
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();

        org.json.JSONArray klines = new org.json.JSONArray(klinesJson);

        for (int i = 0; i < klines.length(); i++) {
            org.json.JSONArray candle = klines.getJSONArray(i);

            long openTime = candle.getLong(0);
            double open = candle.getDouble(1);
            double high = candle.getDouble(2);
            double low = candle.getDouble(3);
            double close = candle.getDouble(4);
            double volume = candle.getDouble(5);

            ZonedDateTime time = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(openTime), ZoneId.systemDefault());

            series.addBar(time, open, high, low, close, volume);
        }

        return series;
    }
}
