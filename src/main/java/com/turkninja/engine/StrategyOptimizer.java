package com.turkninja.engine;

import com.turkninja.engine.criteria.*;
import com.turkninja.config.Config;
import com.turkninja.infra.MockFuturesBinanceService;
import com.turkninja.infra.TelegramNotifier;
import com.turkninja.infra.InfluxDBService;
import com.turkninja.model.BacktestReport;
import com.turkninja.model.optimizer.ParameterSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.Bar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class StrategyOptimizer {
    private static final Logger logger = LoggerFactory.getLogger(StrategyOptimizer.class);

    // Grid Search ranges
    private final double[] rsiShortMin = { 20.0, 25.0, 30.0 };
    private final double[] rsiShortMax = { 50.0, 55.0, 60.0 };
    private final double[] rsiLongMin = { 40.0, 45.0, 50.0 };

    private final double[] stopLossPercents = { 0.01, 0.015, 0.02, 0.03 };
    private final double[] takeProfitPercents = { 0.02, 0.03, 0.04, 0.05 };

    private final double[] trailingActivation = { 0.005, 0.008, 0.01, 0.015 };
    private final double[] trailingDist = { 0.003, 0.005, 0.008 };

    public void optimize(String symbol, List<Bar> history, String timeframe) {
        logger.info("🧠 Starting Strategy Optimization for {}...", symbol);
        logger.info("📊 Data points: {} candles", history.size());

        List<OptimizationResult> results = Collections.synchronizedList(new ArrayList<>());

        // Initialize CSV
        this.currentCsvPath = "backtest_reports/" + symbol + "_optimization_" + System.currentTimeMillis() + ".csv";
        try {
            String header = "Net Profit,Win Rate,Sharpe,Trades,RSI S Min,RSI S Max,RSI L Min,SL,TP,Trail Act,Trail Dist\n";
            Files.writeString(Paths.get(currentCsvPath), header, StandardOpenOption.CREATE);
        } catch (IOException e) {
            logger.error("Failed to init CSV", e);
        }

        long startTime = System.currentTimeMillis();

        // 7-Deep Nested Loop (Grid Search)
        // Ideally this would be recursive or cleaner, but nested loops are fine for now
        // 5. Flatten to List for Parallel Processing
        List<ParameterSetObj> jobQueue = new ArrayList<>();

        for (double rsiSMin : rsiShortMin) {
            for (double rsiSMax : rsiShortMax) {
                if (rsiSMin >= rsiSMax)
                    continue;
                for (double rsiLMin : rsiLongMin) {
                    for (double sl : stopLossPercents) {
                        for (double tp : takeProfitPercents) {
                            if (sl >= tp)
                                continue;
                            for (double trAct : trailingActivation) {
                                for (double trDist : trailingDist) {
                                    if (trDist >= trAct)
                                        continue;

                                    ParameterSet params = new ParameterSet();
                                    params.set("strategy.rsi.short.min", rsiSMin);
                                    params.set("strategy.rsi.short.max", rsiSMax);
                                    params.set("strategy.rsi.long.min", rsiLMin);
                                    jobQueue.add(new ParameterSetObj(params, sl, tp, trAct, trDist));
                                }
                            }
                        }
                    }
                }
            }
        }

        int totalCombinations = jobQueue.size();
        logger.info("🔍 Grid Search Flattened: {} combinations. Running in PARALLEL...", totalCombinations);

        // Initialize CSV
        this.currentCsvPath = "backtest_reports/" + symbol + "_optimization_" + System.currentTimeMillis() + ".csv";
        try {
            String header = "Net Profit,Win Rate,Sharpe,Trades,RSI S Min,RSI S Max,RSI L Min,SL,TP,Trail Act,Trail Dist\n";
            Files.writeString(Paths.get(currentCsvPath), header, StandardOpenOption.CREATE);
        } catch (IOException e) {
            logger.error("Failed to init CSV", e);
        }

        AtomicInteger counter = new AtomicInteger(0);

        // 6. Execute in Parallel
        jobQueue.parallelStream().forEach(job -> {
            runSingleBacktest(symbol, history, timeframe, job.params, job.sl, job.tp, job.trAct, job.trDist,
                    results, counter, totalCombinations);
        });

        long endTime = System.currentTimeMillis();
        logger.info("✅ Optimization complete in {}s", (endTime - startTime) / 1000);

        // Sort and Verify
        results.sort(Comparator.comparingDouble((OptimizationResult r) -> r.netProfit).reversed());

        printTopResults(results, 5);
        // Save full results again just in case
        // saveResultsToCsv(symbol, results); // redundant if incremental works
    }

    // Helper class for flattened jobs
    private static class ParameterSetObj {
        ParameterSet params;
        double sl, tp, trAct, trDist;

        public ParameterSetObj(ParameterSet p, double sl, double tp, double trAct, double trDist) {
            this.params = p;
            this.sl = sl;
            this.tp = tp;
            this.trAct = trAct;
            this.trDist = trDist;
        }
    }

    private String currentCsvPath; // Holds path for incremental saves

    private void runSingleBacktest(String symbol, List<Bar> history, String timeframe,
            ParameterSet params, double sl, double tp, double trAct, double trDist,
            List<OptimizationResult> results, AtomicInteger counter, int total) {

        // 1. Configure Risk Settings via Config (since MockRiskManager reads from it)
        Config.setProperty("risk.stop_loss_percent", String.valueOf(sl));
        Config.setProperty("risk.take_profit_percent", String.valueOf(tp));
        Config.setProperty("strategy.trailing.stop.percent", String.valueOf(trDist));
        Config.setProperty("strategy.trailing.activation.threshold", String.valueOf(trAct));

        // 2. Setup Mock Services
        MockFuturesBinanceService mockService = new MockFuturesBinanceService(10000.0); // Fixed 10000 START
        IndicatorService indicatorService = new IndicatorService();
        PositionTracker positionTracker = new PositionTracker();

        // 3. Setup Risk Manager
        // It will read the Config values we just set
        MockRiskManager riskManager = new MockRiskManager(positionTracker, mockService);
        positionTracker.setRiskManager(riskManager);

        // 4. Setup Strategy Engine with Params
        StrategyEngine strategyEngine = new StrategyEngine(
                null, // WebSocket
                mockService,
                indicatorService,
                riskManager,
                positionTracker,
                null, // OrderBook
                null, // Telegram
                null, // Influx
                params // <--- Injected Strategy Params
        );

        // 5. Run Backtest
        // Correct constructor: StrategyEngine, MockFuturesBinanceService, REAL
        // FuturesBinanceService (null for mock), IndicatorService
        BacktestEngine backtestEngine = new BacktestEngine(strategyEngine, mockService, null, indicatorService);
        BacktestReport report = backtestEngine.runBacktest(symbol, history, timeframe);

        // 6. Store Result
        if (report != null && report.totalTrades > 0) { // Relaxed filter
            OptimizationResult r = new OptimizationResult(params, sl, tp, trAct, trDist, report);
            results.add(r);

            // Incremental Save
            synchronized (this) {
                try {
                    String csvLine = String.format(java.util.Locale.US,
                            "%.2f,%.2f,%.2f,%d,%.1f,%.1f,%.1f,%.4f,%.4f,%.4f,%.4f%n",
                            r.netProfit, r.winRate, r.sharpe, r.trades,
                            r.params.get("strategy.rsi.short.min"), r.params.get("strategy.rsi.short.max"),
                            r.params.get("strategy.rsi.long.min"),
                            r.sl, r.tp, r.trAct, r.trDist);
                    if (currentCsvPath != null) {
                        Files.writeString(Paths.get(currentCsvPath), csvLine, StandardOpenOption.APPEND);
                    }
                } catch (Exception e) {
                    /* ignore */ }
            }
        }

        // Progress Logging
        int c = counter.incrementAndGet();
        if (c % 50 == 0) {
            System.out.print(".");
            if (c % 1000 == 0)
                System.out.println(" " + c + "/" + total);
        }
    }

    private void printTopResults(List<OptimizationResult> results, int topN) {
        System.out.println("\n\n🏆 TOP " + topN + " OPTIMIZATION RESULTS 🏆");
        System.out.println("=================================================================================");
        System.out.printf("%-10s | %-10s | %-10s | %-10s | %-15s | %-10s | %-10s%n",
                "Net PnL %", "Win Rate %", "Sharpe", "Trades", "RSI(S/L)", "SL/TP", "Trail(A/D)");
        System.out.println("---------------------------------------------------------------------------------");

        for (int i = 0; i < Math.min(topN, results.size()); i++) {
            OptimizationResult r = results.get(i);
            System.out.printf("%9.2f%% | %9.2f%% | %10.2f | %10d | %.0f-%.0f/%.0f | %.1f/%.1f%% | %.1f/%.1f%%%n",
                    r.netProfit, r.winRate, r.sharpe, r.trades,
                    r.params.get("strategy.rsi.short.min"), r.params.get("strategy.rsi.short.max"),
                    r.params.get("strategy.rsi.long.min"),
                    r.sl * 100, r.tp * 100, r.trAct * 100, r.trDist * 100);
        }
        System.out.println("=================================================================================\n");
    }

    private void saveResultsToCsv(String symbol, List<OptimizationResult> results) {
        StringBuilder csv = new StringBuilder();
        csv.append("Net Profit,Win Rate,Sharpe,Trades,RSI S Min,RSI S Max,RSI L Min,SL,TP,Trail Act,Trail Dist\n");

        for (OptimizationResult r : results) {
            csv.append(String.format("%.2f,%.2f,%.2f,%d,%.1f,%.1f,%.1f,%.4f,%.4f,%.4f,%.4f%n",
                    r.netProfit, r.winRate, r.sharpe, r.trades,
                    r.params.get("strategy.rsi.short.min"), r.params.get("strategy.rsi.short.max"),
                    r.params.get("strategy.rsi.long.min"),
                    r.sl, r.tp, r.trAct, r.trDist));
        }

        try {
            String filename = "backtest_reports/" + symbol + "_optimization_" + System.currentTimeMillis() + ".csv";
            Files.writeString(Paths.get(filename), csv.toString(), StandardOpenOption.CREATE);
            logger.info("📄 Detailed optimization results saved to: " + filename);
        } catch (IOException e) {
            logger.error("Failed to save optimization CSV", e);
        }
    }

    // Result Holder
    private static class OptimizationResult {
        ParameterSet params;
        double sl, tp, trAct, trDist;
        double netProfit, winRate, sharpe;
        int trades;

        public OptimizationResult(ParameterSet params, double sl, double tp, double trAct, double trDist,
                BacktestReport report) {
            this.params = params;
            this.sl = sl;
            this.tp = tp;
            this.trAct = trAct;
            this.trDist = trDist;

            this.netProfit = report.getNetProfitPercent();
            this.winRate = report.winRate;
            this.sharpe = report.sharpeRatio;
            this.trades = report.totalTrades;
        }
    }
}
