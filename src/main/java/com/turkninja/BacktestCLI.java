package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.*;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.MockFuturesBinanceService;
import com.turkninja.model.BacktestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backtest CLI - Command Line Interface for running backtests
 * 
 * Usage:
 * java -cp target/classes com.turkninja.BacktestCLI BTCUSDT 2024-01-01
 * 2024-06-01 15m
 * 
 * Arguments:
 * 1. symbol - Trading pair (e.g., BTCUSDT, ETHUSDT)
 * 2. startDate - Start date (YYYY-MM-DD)
 * 3. endDate - End date (YYYY-MM-DD)
 * 4. timeframe - Candle timeframe (15m, 1h, 4h)
 */
public class BacktestCLI {

    private static final Logger logger = LoggerFactory.getLogger(BacktestCLI.class);

    public static void main(String[] args) {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("   TurkNinja Backtest Engine v1.0");
        System.out.println("═══════════════════════════════════════════════════════════");

        // Parse arguments
        if (args.length < 4) {
            printUsage();
            System.exit(1);
        }

        String symbol = args[0].toUpperCase();
        String startDate = args[1];
        String endDate = args[2];
        String timeframe = args[3];

        // Validate inputs
        if (!isValidDate(startDate) || !isValidDate(endDate)) {
            System.err.println("❌ Invalid date format. Use YYYY-MM-DD");
            System.exit(1);
        }

        if (!isValidTimeframe(timeframe)) {
            System.err.println("❌ Invalid timeframe. Use: 1m, 5m, 15m, 1h, 4h, 1d");
            System.exit(1);
        }

        System.out.println();
        System.out.println("📊 Backtest Parameters:");
        System.out.println("   Symbol:    " + symbol);
        System.out.println("   Period:    " + startDate + " to " + endDate);
        System.out.println("   Timeframe: " + timeframe);
        System.out.println("   Initial:   $" + Config.getDouble("backtest.initial_balance", 1000.0));
        System.out.println();

        try {
            // Initialize services
            FuturesBinanceService realBinanceService = new FuturesBinanceService(true); // Skip init for backtest
            MockFuturesBinanceService mockService = new MockFuturesBinanceService();
            IndicatorService indicatorService = new IndicatorService();

            // Create PositionTracker for backtest (essential for trade execution)
            PositionTracker positionTracker = new PositionTracker();

            // Create StrategyEngine with PositionTracker
            StrategyEngine strategyEngine = new StrategyEngine(
                    null, // No WebSocket in backtest
                    mockService, // Use mock service as FuturesBinanceService
                    indicatorService,
                    null, // RiskManager - not used in backtest
                    positionTracker, // PositionTracker for trade tracking
                    null, // No OrderBookService
                    null, // No TelegramNotifier
                    null // No InfluxDBService
            );

            // Create BacktestEngine
            BacktestEngine backtestEngine = new BacktestEngine(
                    strategyEngine,
                    mockService,
                    realBinanceService,
                    indicatorService);
            // Run backtest
            System.out.println("🚀 Starting backtest...");
            System.out.println();

            long startTime = System.currentTimeMillis();
            BacktestReport report = backtestEngine.runBacktest(symbol, startDate, endDate, timeframe);
            long duration = System.currentTimeMillis() - startTime;

            if (report == null) {
                System.err.println("❌ Backtest failed! Check logs for details.");
                System.exit(1);
            }

            // Print results
            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println("   BACKTEST RESULTS");
            System.out.println("═══════════════════════════════════════════════════════════");
            System.out.println(report.getSummary());
            System.out.println();
            System.out.println("⏱️  Execution time: " + (duration / 1000.0) + " seconds");

            // Export reports
            String reportDir = "backtest_reports";
            java.nio.file.Files.createDirectories(java.nio.file.Paths.get(reportDir));

            String timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String baseName = String.format("%s/%s_%s_%s_%s", reportDir, symbol, timeframe, startDate, timestamp);

            // Save JSON report
            String jsonPath = baseName + ".json";
            java.nio.file.Files.writeString(java.nio.file.Paths.get(jsonPath), report.toJson());
            System.out.println("📄 JSON report saved: " + jsonPath);

            // Save CSV trades
            String csvPath = baseName + "_trades.csv";
            saveTradesToCSV(report, csvPath);
            System.out.println("📊 Trade log saved: " + csvPath);

            System.out.println();
            System.out.println("✅ Backtest complete!");

        } catch (Exception e) {
            logger.error("Backtest failed", e);
            System.err.println("❌ Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println(
                "Usage: java -cp target/classes com.turkninja.BacktestCLI <symbol> <startDate> <endDate> <timeframe>");
        System.out.println();
        System.out.println("Arguments:");
        System.out.println("  symbol     - Trading pair (e.g., BTCUSDT, ETHUSDT)");
        System.out.println("  startDate  - Start date in YYYY-MM-DD format");
        System.out.println("  endDate    - End date in YYYY-MM-DD format");
        System.out.println("  timeframe  - Candle timeframe (1m, 5m, 15m, 1h, 4h, 1d)");
        System.out.println();
        System.out.println("Example:");
        System.out.println("  java -cp target/classes com.turkninja.BacktestCLI BTCUSDT 2024-01-01 2024-06-01 15m");
    }

    private static boolean isValidDate(String date) {
        try {
            java.time.LocalDate.parse(date, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isValidTimeframe(String tf) {
        return tf.matches("^(1m|5m|15m|30m|1h|2h|4h|1d)$");
    }

    private static void saveTradesToCSV(BacktestReport report, String path) throws Exception {
        StringBuilder csv = new StringBuilder();
        csv.append("Entry Time,Exit Time,Side,Entry Price,Exit Price,Quantity,PnL,PnL %,Commission\n");

        for (BacktestReport.TradeEntry trade : report.trades) {
            csv.append(String.format("%s,%s,%s,%.8f,%.8f,%.8f,%.4f,%.2f,%.4f\n",
                    trade.entryTime != null ? trade.entryTime : "",
                    trade.exitTime != null ? trade.exitTime : "",
                    trade.side,
                    trade.entryPrice,
                    trade.exitPrice,
                    trade.quantity,
                    trade.pnl,
                    trade.pnlPercent,
                    trade.commission));
        }

        java.nio.file.Files.writeString(java.nio.file.Paths.get(path), csv.toString());
    }
}
