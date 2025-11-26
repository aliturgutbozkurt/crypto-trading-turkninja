package com.turkninja.engine.optimizer;

import com.turkninja.engine.BacktestEngine;
import com.turkninja.engine.IndicatorService;
import com.turkninja.engine.OrderBookService;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.infra.TelegramNotifier;
import com.turkninja.model.BacktestReport;
import com.turkninja.model.optimizer.OptimizationResult;
import com.turkninja.model.optimizer.ParameterPerformance;
import com.turkninja.model.optimizer.ParameterSet;
import com.turkninja.model.optimizer.ParameterSpace;
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

        private final IndicatorService indicatorService;
        private final FuturesWebSocketService webSocketService;
        private final FuturesBinanceService realBinanceService;
        private final TelegramNotifier telegramNotifier;
        private final OrderBookService orderBookService;
        private final boolean enableEarlyStopping;
        private final int earlyStoppingThreshold;

        public GridSearchOptimizer(IndicatorService indicatorService,
                        FuturesWebSocketService webSocketService,
                        FuturesBinanceService realBinanceService,
                        TelegramNotifier telegramNotifier,
                        OrderBookService orderBookService) {
                this(indicatorService, webSocketService, realBinanceService, telegramNotifier, orderBookService, true,
                                100);
        }

        public GridSearchOptimizer(IndicatorService indicatorService,
                        FuturesWebSocketService webSocketService,
                        FuturesBinanceService realBinanceService,
                        TelegramNotifier telegramNotifier,
                        OrderBookService orderBookService,
                        boolean enableEarlyStopping,
                        int earlyStoppingThreshold) {
                this.indicatorService = indicatorService;
                this.webSocketService = webSocketService;
                this.realBinanceService = realBinanceService;
                this.telegramNotifier = telegramNotifier;
                this.orderBookService = orderBookService;
                this.enableEarlyStopping = enableEarlyStopping;
                this.earlyStoppingThreshold = earlyStoppingThreshold;
        }

        @Override
        public OptimizationResult optimize(String symbol, String startDate, String endDate,
                        ParameterSpace parameterSpace) {
                logger.info("🔍 Starting Grid Search Optimization for {}", symbol);
                logger.info("Parameter Space: {} combinations", parameterSpace.getTotalCombinations());

                // Generate all parameter combinations
                List<ParameterSet> parameterSets = parameterSpace.getAllCombinations();
                logger.info("Generated {} parameter sets", parameterSets.size());

                // Track best result
                AtomicReference<ParameterPerformance> bestResult = new AtomicReference<>(null);
                AtomicInteger completed = new AtomicInteger(0);

                // Create result object
                OptimizationResult result = new OptimizationResult(symbol, "grid_search");

                // Run backtests using Virtual Threads (parallel)
                try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        List<CompletableFuture<ParameterPerformance>> futures = new ArrayList<>();

                        for (ParameterSet params : parameterSets) {
                                CompletableFuture<ParameterPerformance> future = CompletableFuture.supplyAsync(() -> {
                                        try {
                                                ParameterPerformance perf = runBacktest(symbol, startDate, endDate,
                                                                params);

                                                int count = completed.incrementAndGet();
                                                if (count % 10 == 0) {
                                                        logger.info("Progress: {}/{} backtests completed", count,
                                                                        parameterSets.size());
                                                }

                                                // Update best result
                                                synchronized (bestResult) {
                                                        if (bestResult.get() == null
                                                                        || perf.getSharpeRatio() > bestResult.get()
                                                                                        .getSharpeRatio()) {
                                                                bestResult.set(perf);
                                                                logger.info("🎯 New best Sharpe: {:.3f} with params: {}",
                                                                                perf.getSharpeRatio(), params);
                                                        }
                                                }

                                                return perf;
                                        } catch (Exception e) {
                                                logger.error("Backtest failed for params: {}", params, e);
                                                return new ParameterPerformance(params, 0, 0, 0, 0, 0, 0);
                                        }
                                }, executor);

                                futures.add(future);
                        }

                        // Wait for all to complete
                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                        // Collect all results
                        for (CompletableFuture<ParameterPerformance> future : futures) {
                                result.addResult(future.join());
                        }

                        // Set best result
                        ParameterPerformance best = bestResult.get();
                        result.setBestResult(best.getParameters(), best.getSharpeRatio(),
                                        best.getWinRate(), best.getMaxDrawdown(),
                                        best.getNetProfit(), best.getProfitFactor());

                        result.complete();

                        logger.info("✅ Optimization complete. Best Sharpe Ratio: {:.3f}", best.getSharpeRatio());

                        return result;

                } catch (Exception e) {
                        logger.error("Optimization failed for {}", symbol, e);
                        throw new RuntimeException("Optimization failed", e);
                }
        }

        /**
         * Run a single backtest with given parameters
         */
        private ParameterPerformance runBacktest(String symbol,
                        String startDate,
                        String endDate,
                        ParameterSet parameters) {

                // 1. Create fresh Mock Binance Service
                com.turkninja.infra.MockFuturesBinanceService mockBinanceService = new com.turkninja.infra.MockFuturesBinanceService(
                                1000.0, // Initial balance
                                0.001, // Fee rate
                                0.001 // Slippage
                );

                // 2. Initialize RiskManager and PositionTracker (Circular Dependency)
                com.turkninja.engine.RiskManager riskManager = new com.turkninja.engine.RiskManager(
                                null, // PositionTracker set later
                                mockBinanceService,
                                orderBookService,
                                new com.turkninja.engine.CorrelationService(mockBinanceService));

                com.turkninja.engine.PositionTracker positionTracker = new com.turkninja.engine.PositionTracker(
                                null, // TradeRepository (mocked as null)
                                riskManager);

                riskManager.setPositionTracker(positionTracker);

                // 3. Create StrategyEngine with parameters
                com.turkninja.engine.StrategyEngine strategyEngine = new com.turkninja.engine.StrategyEngine(
                                webSocketService,
                                mockBinanceService,
                                indicatorService,
                                riskManager,
                                positionTracker,
                                orderBookService,
                                telegramNotifier,
                                parameters // Pass the parameters!
                );

                // 4. Create BacktestEngine
                BacktestEngine backtestEngine = new BacktestEngine(
                                strategyEngine,
                                mockBinanceService,
                                realBinanceService,
                                indicatorService);

                // 5. Run Backtest
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
