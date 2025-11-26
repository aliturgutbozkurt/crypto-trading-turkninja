package com.turkninja.engine.optimizer;

import com.turkninja.engine.BacktestEngine;
import com.turkninja.model.BacktestReport;
import com.turkninja.model.optimizer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Grid Search Optimizer
 * 
 * Exhaustively searches through all parameter combinations to find the optimal
 * set.
 * Uses Virtual Threads for parallel execution.
 */
public class GridSearchOptimizer implements ParameterOptimizer {

    private static final Logger logger = LoggerFactory.getLogger(GridSearchOptimizer.class);

    private final BacktestEngine backtestEngine;
    private final boolean enableEarlyStopping;
    private final int earlyStoppingThreshold;

    public GridSearchOptimizer(BacktestEngine backtestEngine) {
        this(backtestEngine, true, 100);
    }

    public GridSearchOptimizer(BacktestEngine backtestEngine,
            boolean enableEarlyStopping,
            int earlyStoppingThreshold) {
        this.backtestEngine = backtestEngine;
        this.enableEarlyStopping = enableEarlyStopping;
        this.earlyStoppingThreshold = earlyStoppingThreshold;
    }

    @Override
    public OptimizationResult optimize(String symbol,
            String startDate,
            String endDate,
            ParameterSpace parameterSpace) {

        logger.info("🔍 Starting Grid Search Optimization for {}", symbol);
        logger.info("Parameter Space: {} combinations", parameterSpace.getTotalCombinations());

        OptimizationResult result = new OptimizationResult(symbol, "grid_search");

        // Get all parameter combinations
        List<ParameterSet> allCombinations = parameterSpace.getAllCombinations();

        if (allCombinations.isEmpty()) {
            logger.warn("No parameter combinations to test!");
            result.complete();
            return result;
        }

        // Progress tracking
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger badResults = new AtomicInteger(0);
        int totalCombos = allCombinations.size();

        // Track best result with AtomicReference (for lambda)
        AtomicReference<ParameterPerformance> bestPerformance = new AtomicReference<>(null);

        // Use Virtual Thread executor for parallel backtests
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            // Submit all backtest tasks
            List<CompletableFuture<ParameterPerformance>> futures = new ArrayList<>();

            for (ParameterSet params : allCombinations) {
                CompletableFuture<ParameterPerformance> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        // Run backtest with these parameters
                        ParameterPerformance performance = runBacktest(symbol, startDate, endDate, params);

                        // Update progress
                        int done = completed.incrementAndGet();
                        if (done % 10 == 0 || done == totalCombos) {
                            double progress = (done * 100.0) / totalCombos;
                            ParameterPerformance currentBest = bestPerformance.get();
                            logger.info("Progress: {}/{} ({:.1f}%) - Best Sharpe: {:.2f}",
                                    done, totalCombos, progress,
                                    currentBest != null ? currentBest.getSharpeRatio() : 0.0);
                        }

                        // Track bad results for early stopping
                        if (performance.getSharpeRatio() < 0.5) {
                            badResults.incrementAndGet();
                        }

                        return performance;

                    } catch (Exception e) {
                        logger.error("Backtest failed for params: {}", params, e);
                        return null;
                    }
                }, executor);

                futures.add(future);

                // Early stopping check
                if (enableEarlyStopping && badResults.get() > earlyStoppingThreshold) {
                    logger.warn("⚠️ Early stopping triggered: {} bad results", badResults.get());
                    break;
                }
            }

            // Wait for all to complete and find best
            for (CompletableFuture<ParameterPerformance> future : futures) {
                try {
                    ParameterPerformance performance = future.get();
                    if (performance != null) {
                        result.addResult(performance);

                        // Track best
                        ParameterPerformance currentBest = bestPerformance.get();
                        if (currentBest == null ||
                                performance.getFitness() > currentBest.getFitness()) {
                            bestPerformance.set(performance);
                        }
                    }
                } catch (InterruptedException | ExecutionException e) {
                    logger.error("Error getting backtest result", e);
                }
            }

        } catch (Exception e) {
            logger.error("Grid Search failed", e);
        }

        // Set best result
        ParameterPerformance finalBest = bestPerformance.get();
        if (finalBest != null) {
            result.setBestResult(
                    finalBest.getParameters(),
                    finalBest.getSharpeRatio(),
                    finalBest.getWinRate(),
                    finalBest.getMaxDrawdown(),
                    finalBest.getNetProfit(),
                    finalBest.getProfitFactor());

            logger.info("✅ Grid Search Complete!");
            logger.info("Best Sharpe Ratio: {:.2f}", finalBest.getSharpeRatio());
            logger.info("Best Parameters: {}", finalBest.getParameters());
        } else {
            logger.warn("No valid results found!");
        }

        result.complete();
        return result;
    }

    /**
     * Run a single backtest with given parameters
     */
    private ParameterPerformance runBacktest(String symbol,
            String startDate,
            String endDate,
            ParameterSet parameters) {

        // TODO: Apply parameters to strategy before backtest
        // For now, run backtest with default parameters

        BacktestReport report = backtestEngine.runBacktest(symbol, startDate, endDate, "5m");

        if (report == null) {
            return new ParameterPerformance(parameters, 0, 0, 0, 0, 0, 0);
        }

        return new ParameterPerformance(
                parameters,
                report.sharpeRatio,
                report.winRate / 100.0, // Convert to decimal
                report.maxDrawdownPercent / 100.0,
                report.getNetProfitPercent() / 100.0,
                report.profitFactor,
                report.totalTrades);
    }

    @Override
    public String getName() {
        return "Grid Search";
    }
}
