package com.turkninja;

import com.turkninja.engine.*;
import com.turkninja.config.Config;
import com.turkninja.infra.*;
import com.turkninja.model.BacktestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Quick Backtest Runner for All Coins
 * Tests current strategy parameters on all trading symbols
 */
public class QuickBacktestRunner {

    private static final Logger logger = LoggerFactory.getLogger(QuickBacktestRunner.class);

    // All trading symbols
    private static final List<String> ALL_SYMBOLS = Arrays.asList(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT", "DOGEUSDT",
            "XRPUSDT", "MATICUSDT", "LTCUSDT", "ETCUSDT", "SUIUSDT");

    public static void main(String[] args) {
        String startDate = "2025-10-27"; // 1 month ago (updated to current year)
        String endDate = "2025-11-27";

        if (args.length >= 2) {
            startDate = args[0];
            endDate = args[1];
        }

        logger.info("╔══════════════════════════════════════════════════════════╗");
        logger.info("║          QUICK BACKTEST - ALL SYMBOLS                    ║");
        logger.info("╚══════════════════════════════════════════════════════════╝");
        logger.info("");
        logger.info("Period: {} to {}", startDate, endDate);
        logger.info("Symbols: {} coins", ALL_SYMBOLS.size());
        logger.info("");

        try {
            // 1. Initialize Core Services
            // DISABLE BATCH MODE FOR BACKTEST (Critical: Backtest loop doesn't run the
            // batch processor)
            Config.setProperty("strategy.batch.enabled", "false");

            // Disable MTF for backtest (to avoid missing data issues on higher TFs)
            Config.setProperty("strategy.mtf.enabled", "false");

            FuturesBinanceService realBinanceService = new FuturesBinanceService(true); // Skip init for backtest
            IndicatorService indicatorService = new IndicatorService();

            List<BacktestReport> allReports = new ArrayList<>();

            // Run backtest for each symbol
            for (int i = 0; i < ALL_SYMBOLS.size(); i++) {
                String symbol = ALL_SYMBOLS.get(i);

                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                logger.info("Testing {}/{}: {}", i + 1, ALL_SYMBOLS.size(), symbol);
                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                try {
                    // Re-initialize all mock services for each symbol to ensure clean state
                    MockFuturesBinanceService mockService = new MockFuturesBinanceService(1000.0);

                    // Mock WebSocket
                    FuturesWebSocketService mockWebSocketService = new FuturesWebSocketService("mockKey",
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

                    // Dependencies
                    CorrelationService correlationService = new CorrelationService(realBinanceService);
                    RiskManager riskManager = new RiskManager(null, mockService, null, correlationService, null, null);
                    PositionTracker positionTracker = new PositionTracker(riskManager);
                    riskManager.setPositionTracker(positionTracker);

                    TelegramNotifier mockTelegram = new TelegramNotifier() {
                        public void sendMessage(String message) {
                        }

                        public void sendAlert(AlertLevel level, String message) {
                        }
                    };

                    StrategyEngine strategyEngine = new StrategyEngine(
                            mockWebSocketService, mockService, indicatorService, riskManager,
                            positionTracker, null, mockTelegram, null);

                    strategyEngine.setAsyncExecution(false);

                    // Create BacktestEngine
                    BacktestEngine backtestEngine = new BacktestEngine(
                            strategyEngine, mockService, realBinanceService, indicatorService);

                    // Run backtest
                    BacktestReport report = backtestEngine.runBacktest(symbol, startDate, endDate, "5m");

                    if (report != null) {
                        allReports.add(report);
                        printReport(report);
                    } else {
                        logger.warn("❌ No report generated for {}", symbol);
                    }

                } catch (Exception e) {
                    logger.error("❌ Backtest failed for {}", symbol, e);
                }

                logger.info("");
            }

            // Print summary
            printSummary(allReports);

        } catch (Exception e) {
            logger.error("Backtest runner failed", e);
            System.exit(1);
        }
    }

    /**
     * Print individual backtest report
     */
    private static void printReport(BacktestReport report) {
        System.out.println("\n📊 " + report.symbol + " RESULTS:");
        System.out.println("─────────────────────────────────────────");
        System.out.printf("Total Trades:     %d\n", report.totalTrades);
        System.out.printf("Win Rate:         %.1f%%\n", report.winRate);
        System.out.printf("Net Profit:       %.2f%% ($%.2f)\n",
                report.getNetProfitPercent(), report.finalBalance - report.initialBalance);
        System.out.printf("Sharpe Ratio:     %.2f\n", report.sharpeRatio);
        System.out.printf("Max Drawdown:     %.2f%%\n", report.maxDrawdownPercent);
        System.out.printf("Profit Factor:    %.2f\n", report.profitFactor);
        System.out.printf("Final Balance:    $%.2f\n", report.finalBalance);

        String verdict = report.sharpeRatio > 1.5 ? "✅ EXCELLENT"
                : report.sharpeRatio > 1.0 ? "🟢 GOOD" : report.sharpeRatio > 0.5 ? "🟡 MEDIOCRE" : "❌ POOR";
        System.out.println("Verdict:          " + verdict);
    }

    /**
     * Print summary comparison
     */
    private static void printSummary(List<BacktestReport> reports) {
        System.out.println("\n\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    BACKTEST SUMMARY - ALL SYMBOLS                        ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println("");

        System.out.printf("%-12s | %8s | %8s | %8s | %10s | %8s | %s\n",
                "Symbol", "Trades", "Win%", "Sharpe", "Net P/L", "Max DD", "Verdict");
        System.out.println("─────────────┼──────────┼──────────┼──────────┼────────────┼──────────┼──────────");

        double totalProfit = 0;
        int totalTrades = 0;

        for (BacktestReport report : reports) {
            String verdict = report.sharpeRatio > 1.5 ? "EXCELLENT"
                    : report.sharpeRatio > 1.0 ? "GOOD" : report.sharpeRatio > 0.5 ? "MEDIOCRE" : "POOR";

            System.out.printf("%-12s | %8d | %7.1f%% | %8.2f | %9.2f%% | %7.2f%% | %s\n",
                    report.symbol,
                    report.totalTrades,
                    report.winRate,
                    report.sharpeRatio,
                    report.getNetProfitPercent(),
                    report.maxDrawdownPercent,
                    verdict);

            totalProfit += report.getNetProfitPercent();
            totalTrades += report.totalTrades;
        }

        System.out.println("");
        System.out.printf("Total Trades:     %d\n", totalTrades);
        System.out.printf("Average Profit:   %.2f%%\n", totalProfit / reports.size());

        // Find best and worst
        BacktestReport best = reports.stream()
                .max((a, b) -> Double.compare(a.sharpeRatio, b.sharpeRatio))
                .orElse(null);

        BacktestReport worst = reports.stream()
                .min((a, b) -> Double.compare(a.sharpeRatio, b.sharpeRatio))
                .orElse(null);

        if (best != null) {
            System.out.println("");
            System.out.printf("🏆 Best:  %s (Sharpe: %.2f, Win: %.1f%%, Profit: %.2f%%)\n",
                    best.symbol, best.sharpeRatio, best.winRate, best.getNetProfitPercent());
        }

        if (worst != null) {
            System.out.printf("❌ Worst: %s (Sharpe: %.2f, Win: %.1f%%, Profit: %.2f%%)\n",
                    worst.symbol, worst.sharpeRatio, worst.winRate, worst.getNetProfitPercent());
        }

        System.out.println("");
    }
}
