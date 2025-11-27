package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.*;
import com.turkninja.infra.*;
import com.turkninja.model.BacktestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cross-Validation Backtester
 * Tests optimized parameters across multiple symbols and time periods
 */
public class CrossValidationBacktest {

        private static final Logger logger = LoggerFactory.getLogger(CrossValidationBacktest.class);

        // Test symbols
        private static final List<String> SYMBOLS = Arrays.asList(
                        "BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT");

        // Test periods
        private static final List<Period> PERIODS = Arrays.asList(
                        new Period("Last 1 Month", "2024-10-27", "2024-11-27"),
                        new Period("Last 3 Months", "2024-08-27", "2024-11-27"),
                        new Period("Last 6 Months", "2024-05-27", "2024-11-27"));

        public static void main(String[] args) {
                logger.info("╔══════════════════════════════════════════════════════════╗");
                logger.info("║         CROSS-VALIDATION BACKTEST                        ║");
                logger.info("║      Testing Optimized Parameters                        ║");
                logger.info("╚══════════════════════════════════════════════════════════╝");
                logger.info("");
                logger.info("Symbols: {}", SYMBOLS);
                logger.info("Periods: {} time ranges", PERIODS.size());
                logger.info("");

                try {
                        Config.setProperty("strategy.batch.enabled", "false");
                        Config.setProperty("strategy.mtf.enabled", "false");

                        FuturesBinanceService realBinanceService = new FuturesBinanceService(true);
                        IndicatorService indicatorService = new IndicatorService();

                        List<TestResult> allResults = new ArrayList<>();

                        // Test each period
                        for (Period period : PERIODS) {
                                logger.info("═════════════════════════════════════════════════════════");
                                logger.info("Testing Period: {} ({} to {})",
                                                period.name, period.startDate, period.endDate);
                                logger.info("═════════════════════════════════════════════════════════");
                                logger.info("");

                                List<BacktestReport> periodReports = new ArrayList<>();

                                // Test each symbol in this period
                                for (int i = 0; i < SYMBOLS.size(); i++) {
                                        String symbol = SYMBOLS.get(i);

                                        logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                                        logger.info("Testing {}/{}: {} ({})",
                                                        i + 1, SYMBOLS.size(), symbol, period.name);
                                        logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                                        try {
                                                MockFuturesBinanceService mockService = new MockFuturesBinanceService(
                                                                1000.0);

                                                FuturesWebSocketService mockWebSocketService = new FuturesWebSocketService(
                                                                "mockKey",
                                                                "mockSecret") {
                                                        @Override
                                                        public void startUserDataStream() {
                                                        }

                                                        @Override
                                                        public void startKlineStream(List<String> symbols) {
                                                        }

                                                        @Override
                                                        public void startDepthStream(List<String> symbols) {
                                                        }
                                                };

                                                RiskManager riskManager = new RiskManager(null, mockService, null,
                                                                new CorrelationService(realBinanceService), null); // Added
                                                                                                                   // null
                                                                                                                   // for
                                                                                                                   // InfluxDBService
                                                PositionTracker positionTracker = new PositionTracker(riskManager); // Updated
                                                                                                                    // constructor
                                                riskManager.setPositionTracker(positionTracker);

                                                TelegramNotifier mockTelegram = new TelegramNotifier() {
                                                        @Override
                                                        public void sendMessage(String message) {
                                                                // Suppress messages during backtest
                                                        }
                                                };

                                                StrategyEngine strategyEngine = new StrategyEngine(
                                                                mockWebSocketService, mockService, indicatorService,
                                                                riskManager, positionTracker, null, mockTelegram, null);

                                                strategyEngine.setAsyncExecution(false);

                                                BacktestEngine backtestEngine = new BacktestEngine(
                                                                strategyEngine, mockService, realBinanceService,
                                                                indicatorService);

                                                BacktestReport report = backtestEngine.runBacktest(
                                                                symbol, period.startDate, period.endDate, "5m");

                                                if (report != null) {
                                                        periodReports.add(report);
                                                        allResults.add(new TestResult(symbol, period.name, report));

                                                        logger.info("✅ {} - Trades: {}, Win: {:.1f}%, Profit: {:.2f}%, Sharpe: {:.2f}",
                                                                        symbol, report.totalTrades, report.winRate,
                                                                        report.getNetProfitPercent(),
                                                                        report.sharpeRatio);
                                                }

                                        } catch (Exception e) {
                                                logger.error("❌ Backtest failed for {} in {}", symbol, period.name, e);
                                        }

                                        logger.info("");
                                }

                                // Print period summary
                                printPeriodSummary(period.name, periodReports);
                                logger.info("");
                        }

                        // Print final comparison
                        printFinalComparison(allResults);

                } catch (Exception e) {
                        logger.error("Cross-validation failed", e);
                        System.exit(1);
                }
        }

        private static void printPeriodSummary(String periodName, List<BacktestReport> reports) {
                if (reports.isEmpty())
                        return;

                System.out.println("\n📊 " + periodName + " Summary:");
                System.out.println("─────────────────────────────────────────────────────────");

                double avgProfit = reports.stream()
                                .mapToDouble(r -> r.getNetProfitPercent())
                                .average()
                                .orElse(0.0);

                double avgWinRate = reports.stream()
                                .mapToDouble(r -> r.winRate)
                                .average()
                                .orElse(0.0);

                int totalTrades = reports.stream()
                                .mapToInt(r -> r.totalTrades)
                                .sum();

                long profitableSymbols = reports.stream()
                                .filter(r -> r.getNetProfitPercent() > 0)
                                .count();

                System.out.printf("Tested Symbols:       %d%n", reports.size());
                System.out.printf("Profitable Symbols:   %d (%.1f%%)%n",
                                profitableSymbols, (profitableSymbols * 100.0 / reports.size()));
                System.out.printf("Total Trades:         %d%n", totalTrades);
                System.out.printf("Average Win Rate:     %.1f%%%n", avgWinRate);
                System.out.printf("Average Profit:       %.2f%%%n", avgProfit);

                BacktestReport best = reports.stream()
                                .max((a, b) -> Double.compare(a.getNetProfitPercent(), b.getNetProfitPercent()))
                                .orElse(null);

                if (best != null && best.getNetProfitPercent() > 0) {
                        System.out.printf("🏆 Best: %s (%.2f%% profit)%n",
                                        best.symbol, best.getNetProfitPercent());
                }
        }

        private static void printFinalComparison(List<TestResult> results) {
                System.out.println("\n\n");
                System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
                System.out.println("║                    CROSS-VALIDATION RESULTS                                   ║");
                System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");
                System.out.println("");

                System.out.printf("%-12s | %-16s | %8s | %8s | %10s | %8s | %8s%n",
                                "Symbol", "Period", "Trades", "Win%", "Profit", "Sharpe", "Max DD");
                System.out.println(
                                "─────────────┼──────────────────┼──────────┼──────────┼────────────┼──────────┼──────────");

                for (TestResult result : results) {
                        BacktestReport r = result.report;
                        System.out.printf("%-12s | %-16s | %8d | %7.1f%% | %9.2f%% | %8.2f | %7.2f%%%n",
                                        result.symbol,
                                        result.periodName,
                                        r.totalTrades,
                                        r.winRate,
                                        r.getNetProfitPercent(),
                                        r.sharpeRatio,
                                        r.maxDrawdownPercent);
                }

                System.out.println("");

                // Best by symbol
                System.out.println("📈 BEST PERFORMANCE BY SYMBOL:");
                System.out.println("─────────────────────────────────────────");
                for (String symbol : SYMBOLS) {
                        TestResult best = results.stream()
                                        .filter(r -> r.symbol.equals(symbol))
                                        .max((a, b) -> Double.compare(a.report.getNetProfitPercent(),
                                                        b.report.getNetProfitPercent()))
                                        .orElse(null);

                        if (best != null) {
                                System.out.printf("%-12s: %.2f%% (%s)%n",
                                                symbol, best.report.getNetProfitPercent(), best.periodName);
                        }
                }

                System.out.println("");

                // Best by period
                System.out.println("📅 AVERAGE PERFORMANCE BY PERIOD:");
                System.out.println("─────────────────────────────────────────");
                for (Period period : PERIODS) {
                        double avgProfit = results.stream()
                                        .filter(r -> r.periodName.equals(period.name))
                                        .mapToDouble(r -> r.report.getNetProfitPercent())
                                        .average()
                                        .orElse(0.0);

                        System.out.printf("%-16s: %.2f%% avg%n", period.name, avgProfit);
                }

                System.out.println("");

                // Overall best
                TestResult overallBest = results.stream()
                                .max((a, b) -> Double.compare(a.report.sharpeRatio, b.report.sharpeRatio))
                                .orElse(null);

                if (overallBest != null) {
                        System.out.println("🏆 OVERALL BEST RESULT:");
                        System.out.printf("   %s - %s%n", overallBest.symbol, overallBest.periodName);
                        System.out.printf("   Sharpe: %.2f | Win: %.1f%% | Profit: %.2f%% | Trades: %d%n",
                                        overallBest.report.sharpeRatio,
                                        overallBest.report.winRate,
                                        overallBest.report.getNetProfitPercent(),
                                        overallBest.report.totalTrades);
                }

                System.out.println("");
        }

        static class Period {
                String name;
                String startDate;
                String endDate;

                Period(String name, String startDate, String endDate) {
                        this.name = name;
                        this.startDate = startDate;
                        this.endDate = endDate;
                }
        }

        static class TestResult {
                String symbol;
                String periodName;
                BacktestReport report;

                TestResult(String symbol, String periodName, BacktestReport report) {
                        this.symbol = symbol;
                        this.periodName = periodName;
                        this.report = report;
                }
        }
}
