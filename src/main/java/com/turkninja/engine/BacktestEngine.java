package com.turkninja.engine;

import com.turkninja.config.Config;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.MockFuturesBinanceService;
import com.turkninja.model.BacktestReport;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.DoubleNum;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backtest Engine
 * 
 * Simulates strategy execution on historical data.
 * Uses MockFuturesBinanceService to track virtual positions and PnL.
 */
public class BacktestEngine {

    private static final Logger logger = LoggerFactory.getLogger(BacktestEngine.class);

    private final StrategyEngine strategyEngine;
    private final MockFuturesBinanceService mockFuturesService;
    private final FuturesBinanceService realBinanceService; // For fetching historical data
    private final IndicatorService indicatorService;

    public BacktestEngine(StrategyEngine strategyEngine,
            MockFuturesBinanceService mockFuturesService,
            FuturesBinanceService realBinanceService,
            IndicatorService indicatorService) {
        this.strategyEngine = strategyEngine;
        this.mockFuturesService = mockFuturesService;
        this.realBinanceService = realBinanceService;
        this.indicatorService = indicatorService;
    }

    /**
     * Run backtest for a specific symbol and date range
     *
     * @param symbol    Trading symbol (e.g., "ETHUSDT")
     * @param startDate Start date (YYYY-MM-DD format)
     * @param endDate   End date (YYYY-MM-DD format)
     * @param timeframe Timeframe (e.g., "5m", "15m", "1h")
     * @return BacktestReport with results
     */
    public BacktestReport runBacktest(String symbol, String startDate, String endDate, String timeframe) {
        logger.info("🚀 Starting backtest for {} ({}) from {} to {}",
                symbol, timeframe, startDate, endDate);

        try {
            // 1. Load historical data
            List<Bar> history = loadHistoricalData(symbol, startDate, endDate, timeframe);

            if (history.isEmpty()) {
                logger.error("❌ No historical data loaded");
                return null;
            }

            // 2. Run simulation
            return runBacktest(symbol, history, timeframe);

        } catch (Exception e) {
            logger.error("❌ Backtest failed", e);
            return null;
        }
    }

    /**
     * Run backtest for a specific symbol and data
     */
    public BacktestReport runBacktest(String symbol, List<Bar> history, String timeframe) {
        logger.info("🚀 Starting backtest for {} ({}) with {} candles", symbol, timeframe, history.size());

        // Reset mock service
        double initialBalance = Config.getDouble("backtest.initial_balance", 1000.0);
        mockFuturesService.reset(initialBalance);

        // Create report builder
        BacktestReport report = new BacktestReport();
        report.symbol = symbol;
        report.timeframe = timeframe;
        report.initialBalance = initialBalance;
        if (!history.isEmpty()) {
            report.startTime = history.get(0).getEndTime().atZone(ZoneId.of("UTC"));
            report.endTime = history.get(history.size() - 1).getEndTime().atZone(ZoneId.of("UTC"));
        }

        // Build BarSeries
        BarSeries series = new BaseBarSeriesBuilder()
                .withName(symbol)
                .build();

        double peak = initialBalance;

        // Optimize: Pre-calculate indicators for O(N) performance
        try {
            Map<String, org.ta4j.core.Indicator<org.ta4j.core.num.Num>> indicators = indicatorService
                    .getIndicators(series);
            strategyEngine.setCachedIndicators(indicators);
            logger.info("✅ Indicators pre-calculated for optimization");
        } catch (Exception e) {
            logger.warn("Failed to pre-calculate indicators, falling back to slow mode: {}", e.getMessage());
        }

        // Simulation Loop
        for (int i = 0; i < history.size(); i++) {
            Bar bar = history.get(i);

            // 1. Update Market Data
            series.addBar(bar);
            double closePrice = bar.getClosePrice().doubleValue();

            // Update time in mock service
            long candleTime = bar.getEndTime().toEpochMilli();
            mockFuturesService.setCurrentTime(candleTime);
            mockFuturesService.setCurrentPrice(symbol, closePrice);

            // Check for exits (SL/TP/Trailing) - if RiskManager is available
            if (strategyEngine.getRiskManager() != null) {
                strategyEngine.getRiskManager().onPriceUpdate(symbol, closePrice);
            }

            // 2. Execute Strategy Logic (only if we have enough bars for indicators)
            if (i >= 50) { // Need 50+ bars for indicators like EMA50
                try {
                    strategyEngine.analyzeAndTrade(symbol, series);
                } catch (Exception e) {
                    logger.error("Error in backtest step {}: {}", i, e.getMessage());
                }
            }

            // 3. Record Equity (every 100 bars to avoid too much data)
            if (i % 100 == 0 || i == history.size() - 1) {
                double currentEquity = mockFuturesService.getVirtualBalance();

                // Update peak and calculate drawdown
                if (currentEquity > peak) {
                    peak = currentEquity;
                }
                double drawdown = currentEquity < peak ? ((peak - currentEquity) / peak) * 100 : 0;

                report.equityCurve.add(new BacktestReport.EquityPoint(
                        bar.getEndTime().atZone(ZoneId.of("UTC")), currentEquity, drawdown));

                if (i % 500 == 0) { // Log progress every 500 bars
                    logger.info("📊 Progress: {}/{} bars, Equity: ${:.2f}",
                            i, history.size(), currentEquity);
                }
            }
        }

        // Close all open positions at end
        try {
            mockFuturesService.closePosition(symbol);
        } catch (Exception e) {
            // Ignore
        }

        // Clear cached indicators to prevent side effects
        strategyEngine.setCachedIndicators(null);

        // Finalize Report
        report.finalBalance = mockFuturesService.getVirtualBalance();
        report.netProfit = report.finalBalance - report.initialBalance;

        // Get trades from mock service
        report.trades = mockFuturesService.getTradeHistory();

        // Calculate all metrics
        report.calculateMetrics();

        logger.info("🏁 Backtest complete. Net Profit: ${:.2f} ({:.2f}%)", report.netProfit,
                report.getNetProfitPercent());

        return report;
    }

    /**
     * Load historical data from Binance
     *
     * @param symbol    Trading symbol
     * @param startDate Start date (YYYY-MM-DD)
     * @param endDate   End date (YYYY-MM-DD)
     * @param interval  Timeframe (5m, 15m, 1h, etc.)
     * @return List of Bars
     */
    public List<Bar> loadHistoricalData(String symbol, String startDate, String endDate, String interval) {
        List<Bar> bars = new ArrayList<>();
        String cacheDir = "backtest_data";
        String fileName = String.format("%s/%s_%s_%s_%s.json", cacheDir, symbol, interval, startDate, endDate);

        try {
            // Ensure cache directory exists
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(cacheDir));

            java.io.File cacheFile = new java.io.File(fileName);
            JSONArray allKlines;

            if (cacheFile.exists()) {
                logger.info("📂 Loading cached data for {} from {}", symbol, fileName);
                String klineData = new String(java.nio.file.Files.readAllBytes(cacheFile.toPath()));
                allKlines = new JSONArray(klineData);
            } else {
                logger.info("📥 Downloading historical data: {} {} from {} to {}",
                        symbol, interval, startDate, endDate);

                // Parse dates and convert to timestamps
                java.time.LocalDate start = java.time.LocalDate.parse(startDate);
                java.time.LocalDate end = java.time.LocalDate.parse(endDate);
                long startTimeMs = start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                long endTimeMs = end.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();

                allKlines = new JSONArray();
                int limit = 1500;
                long currentStartMs = startTimeMs;
                long intervalMs = getIntervalMillis(interval);

                // Paginated loading
                while (currentStartMs < endTimeMs) {
                    String klineData = realBinanceService.getKlinesWithTime(symbol, interval, limit, currentStartMs,
                            endTimeMs);

                    if (klineData == null || klineData.isEmpty() || klineData.equals("[]")) {
                        break;
                    }

                    JSONArray batch = new JSONArray(klineData);
                    if (batch.length() == 0) {
                        break;
                    }

                    // Add all candles from this batch
                    for (int i = 0; i < batch.length(); i++) {
                        allKlines.put(batch.getJSONArray(i));
                    }

                    // Move to next batch
                    JSONArray lastCandle = batch.getJSONArray(batch.length() - 1);
                    long lastCloseTime = lastCandle.getLong(6);
                    currentStartMs = lastCloseTime + 1;

                    logger.info("📊 Downloaded {} candles (total: {})", batch.length(), allKlines.length());

                    // Rate limit protection
                    Thread.sleep(100);
                }

                // Save to cache
                if (allKlines.length() > 0) {
                    try {
                        java.nio.file.Files.write(cacheFile.toPath(), allKlines.toString().getBytes());
                        logger.info("💾 Saved {} candles to cache: {}", allKlines.length(), fileName);
                    } catch (Exception e) {
                        logger.warn("Failed to save cache file", e);
                    }
                }
            }

            if (allKlines.length() == 0) {
                logger.warn("⚠️ No data received for {}", symbol);
                return bars;
            }

            // Create BarSeries
            BarSeries series = new BaseBarSeriesBuilder()
                    .withName(symbol)
                    .build();

            // Convert to Bars
            for (int i = 0; i < allKlines.length(); i++) {
                JSONArray kline = allKlines.getJSONArray(i);

                long openTime = kline.getLong(0);
                double open = kline.getDouble(1);
                double high = kline.getDouble(2);
                double low = kline.getDouble(3);
                double close = kline.getDouble(4);
                double volume = kline.getDouble(5);

                Duration duration = Duration.ofMillis(getIntervalMillis(interval));
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
                        DecimalNum.valueOf(0),
                        0L);
                series.addBar(bar);
            }

            // Convert series to list of bars
            for (int i = 0; i < series.getBarCount(); i++) {
                bars.add(series.getBar(i));
            }

            logger.info("✅ Loaded {} candles for {}", bars.size(), symbol);

        } catch (Exception e) {
            logger.error("❌ Failed to load historical data", e);
        }

        return bars;
    }

    /**
     * Convert interval string to milliseconds
     */
    private long getIntervalMillis(String interval) {
        return switch (interval) {
            case "1m" -> 60 * 1000L;
            case "5m" -> 5 * 60 * 1000L;
            case "15m" -> 15 * 60 * 1000L;
            case "1h" -> 60 * 60 * 1000L;
            case "4h" -> 4 * 60 * 60 * 1000L;
            case "1d" -> 24 * 60 * 60 * 1000L;
            default -> 5 * 60 * 1000L; // Default 5m
        };
    }
}
