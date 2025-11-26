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
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DecimalNum;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
            report.startTime = history.get(0).getEndTime();
            report.endTime = history.get(history.size() - 1).getEndTime();
        }

        // Build BarSeries
        BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();

        double peak = initialBalance;

        // Simulation Loop
        for (int i = 0; i < history.size(); i++) {
            Bar bar = history.get(i);

            // 1. Update Market Data
            series.addBar(bar);
            double closePrice = bar.getClosePrice().doubleValue();
            mockFuturesService.setCurrentPrice(symbol, closePrice);

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
                        bar.getEndTime(), currentEquity, drawdown));

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
            logger.debug("No position to close at end of backtest");
        }

        // Finalize Report
        report.finalBalance = mockFuturesService.getVirtualBalance();
        report.netProfit = report.finalBalance - report.initialBalance;

        // Get trades from mock service
        report.trades = mockFuturesService.getTradeHistory();

        // Calculate all metrics
        report.calculateMetrics();

        logger.info("🏁 Backtest complete. Net Profit: ${:.2f} ({:.2f}%)",
                report.netProfit, report.getNetProfitPercent());

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
    private List<Bar> loadHistoricalData(String symbol, String startDate, String endDate, String interval) {
        List<Bar> bars = new ArrayList<>();

        try {
            logger.info("📥 Loading historical data: {} {} from {} to {}",
                    symbol, interval, startDate, endDate);

            // Binance allows max 1500 candles per request
            // For backtest, we'll load in chunks
            int limit = 1500;

            // Fetch klines (Binance returns most recent if no date params)
            String klineData = realBinanceService.getKlines(symbol, interval, limit);

            if (klineData == null || klineData.isEmpty()) {
                logger.warn("⚠️ No data received for {}", symbol);
                return bars;
            }

            // Parse JSON
            JSONArray klines = new JSONArray(klineData);

            if (klines.length() == 0) {
                logger.warn("⚠️ Empty kline data for {}", symbol);
                return bars;
            }

            long intervalMs = getIntervalMillis(interval);

            // Convert to Bars
            for (int i = 0; i < klines.length(); i++) {
                JSONArray kline = klines.getJSONArray(i);

                long openTime = kline.getLong(0);
                double open = kline.getDouble(1);
                double high = kline.getDouble(2);
                double low = kline.getDouble(3);
                double close = kline.getDouble(4);
                double volume = kline.getDouble(5);
                long closeTime = kline.getLong(6);

                ZonedDateTime endTimestamp = ZonedDateTime.ofInstant(
                        Instant.ofEpochMilli(closeTime), ZoneId.of("UTC"));

                Bar bar = new org.ta4j.core.BaseBar(
                        Duration.ofMillis(intervalMs),
                        endTimestamp,
                        open,
                        high,
                        low,
                        close,
                        volume);

                bars.add(bar);
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
