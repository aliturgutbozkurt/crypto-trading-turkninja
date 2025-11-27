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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Genetic Algorithm Optimizer
 * 
 * Uses evolutionary principles to find optimal parameters efficiently.
 * - Selection: Tournament
 * - Crossover: Uniform
 * - Mutation: Random
 * - Elitism: Preserves best individual
 */
public class GeneticOptimizer implements ParameterOptimizer {

    private static final Logger logger = LoggerFactory.getLogger(GeneticOptimizer.class);

    private final IndicatorService indicatorService;
    private final FuturesWebSocketService webSocketService;
    private final FuturesBinanceService realBinanceService;
    private final TelegramNotifier telegramNotifier;
    private final OrderBookService orderBookService;

    // GA Parameters
    private static final int POPULATION_SIZE = 20;
    private static final int GENERATIONS = 10;
    private static final double MUTATION_RATE = 0.1;
    private static final double CROSSOVER_RATE = 0.8;
    private static final int TOURNAMENT_SIZE = 3;
    private static final int ELITISM_COUNT = 2;

    private final Random random = new Random();

    public GeneticOptimizer(IndicatorService indicatorService,
            FuturesWebSocketService webSocketService,
            FuturesBinanceService realBinanceService,
            TelegramNotifier telegramNotifier,
            OrderBookService orderBookService) {
        this.indicatorService = indicatorService;
        // WebSocket service not used in optimization
        this.webSocketService = null;
        this.realBinanceService = realBinanceService;
        this.telegramNotifier = telegramNotifier;
        this.orderBookService = orderBookService;
    }

    @Override
    public OptimizationResult optimize(String symbol, String startDate, String endDate,
            ParameterSpace parameterSpace) {
        logger.info("🧬 Starting Genetic Optimization for {}", symbol);
        logger.info("Generations: {}, Population: {}", GENERATIONS, POPULATION_SIZE);

        OptimizationResult result = new OptimizationResult(symbol, "genetic");

        // 1. Initialize Population
        List<ParameterSet> population = initializePopulation(parameterSpace);
        ParameterPerformance globalBest = null;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            for (int gen = 0; gen < GENERATIONS; gen++) {
                logger.info("🔄 Generation {}/{}", gen + 1, GENERATIONS);

                // 2. Evaluate Fitness (Parallel)
                List<CompletableFuture<ParameterPerformance>> futures = new ArrayList<>();
                for (ParameterSet params : population) {
                    futures.add(CompletableFuture.supplyAsync(() -> runBacktest(symbol, startDate, endDate, params),
                            executor)
                            .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // Timeout the future itself
                            .exceptionally(ex -> {
                                logger.error("Backtest failed or timed out", ex);
                                return new ParameterPerformance(new ParameterSet(), -100, 0, 1, -1, 0, 0); // Penalize
                                                                                                           // failure
                            }));
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                List<ParameterPerformance> performances = futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList());

                // 3. Find Best in Generation
                ParameterPerformance genBest = performances.stream()
                        .max(Comparator.comparingDouble(ParameterPerformance::getSharpeRatio))
                        .orElseThrow();

                logger.info("   Best Sharpe: {:.3f} (Win: {:.1f}%, Profit: {:.1f}%)",
                        genBest.getSharpeRatio(), genBest.getWinRate() * 100, genBest.getNetProfit() * 100);

                if (globalBest == null || genBest.getSharpeRatio() > globalBest.getSharpeRatio()) {
                    globalBest = genBest;
                    logger.info("   🏆 New Global Best!");
                }

                // Add to results
                result.addResult(genBest);

                // 4. Create Next Generation
                population = evolve(performances, parameterSpace);
            }

            // Final Result
            if (globalBest != null) {
                result.setBestResult(globalBest.getParameters(), globalBest.getSharpeRatio(),
                        globalBest.getWinRate(), globalBest.getMaxDrawdown(),
                        globalBest.getNetProfit(), globalBest.getProfitFactor());
            }

            result.complete();
            logger.info("✅ Optimization complete. Best Sharpe: {:.3f}",
                    globalBest != null ? globalBest.getSharpeRatio() : 0);

            return result;

        } catch (Exception e) {
            logger.error("Optimization failed", e);
            throw new RuntimeException("Optimization failed", e);
        }
    }

    private List<ParameterSet> initializePopulation(ParameterSpace space) {
        List<ParameterSet> population = new ArrayList<>();
        for (int i = 0; i < POPULATION_SIZE; i++) {
            population.add(space.randomSample());
        }
        return population;
    }

    private List<ParameterSet> evolve(List<ParameterPerformance> rankedPopulation, ParameterSpace space) {
        List<ParameterSet> nextGen = new ArrayList<>();

        // Sort by fitness (Sharpe Ratio) descending
        rankedPopulation.sort((a, b) -> Double.compare(b.getSharpeRatio(), a.getSharpeRatio()));

        // Elitism: Keep best individuals
        for (int i = 0; i < ELITISM_COUNT; i++) {
            if (i < rankedPopulation.size()) {
                nextGen.add(rankedPopulation.get(i).getParameters());
            }
        }

        // Fill rest of population
        while (nextGen.size() < POPULATION_SIZE) {
            ParameterSet p1 = tournamentSelect(rankedPopulation);
            ParameterSet p2 = tournamentSelect(rankedPopulation);

            ParameterSet child;
            if (random.nextDouble() < CROSSOVER_RATE) {
                child = crossover(p1, p2);
            } else {
                child = p1.copy();
            }

            mutate(child, space);
            nextGen.add(child);
        }

        return nextGen;
    }

    private ParameterSet tournamentSelect(List<ParameterPerformance> population) {
        ParameterPerformance best = null;
        for (int i = 0; i < TOURNAMENT_SIZE; i++) {
            ParameterPerformance candidate = population.get(random.nextInt(population.size()));
            if (best == null || candidate.getSharpeRatio() > best.getSharpeRatio()) {
                best = candidate;
            }
        }
        return best.getParameters();
    }

    private ParameterSet crossover(ParameterSet p1, ParameterSet p2) {
        ParameterSet child = new ParameterSet();
        for (String key : p1.getAll().keySet()) {
            if (random.nextDouble() < 0.5) {
                child.set(key, p1.get(key));
            } else {
                child.set(key, p2.get(key));
            }
        }
        return child;
    }

    private void mutate(ParameterSet params, ParameterSpace space) {
        for (String key : params.getAll().keySet()) {
            if (random.nextDouble() < MUTATION_RATE) {
                // Replace with random value from valid range
                params.set(key, space.getRange(key).getRandomValue());
            }
        }
    }

    private ParameterPerformance runBacktest(String symbol, String startDate, String endDate, ParameterSet params) {
        // Create fresh Mock Binance Service
        com.turkninja.infra.MockFuturesBinanceService mockBinanceService = new com.turkninja.infra.MockFuturesBinanceService(
                1000.0, 0.001, 0.001);

        // Mock WebSocket Service
        FuturesWebSocketService mockWebSocketService = new FuturesWebSocketService("mockKey", "mockSecret") {
            @Override
            public void startUserDataStream() {
            }

            @Override
            public void startKlineStream(List<String> symbols) {
            }

            @Override
            public void startDepthStream(List<String> symbols) {
            }

            @Override
            public List<org.json.JSONObject> getCachedKlines(String symbol, String interval, int limit) {
                return new ArrayList<>();
            }
        };

        // Initialize Services
        com.turkninja.engine.CorrelationService correlationService = new com.turkninja.engine.CorrelationService(
                mockBinanceService);
        com.turkninja.engine.RiskManager riskManager = new com.turkninja.engine.RiskManager(null, mockBinanceService,
                orderBookService, correlationService, null, null); // null InfluxDB and Telegram
        com.turkninja.engine.PositionTracker positionTracker = new com.turkninja.engine.PositionTracker(riskManager);
        riskManager.setPositionTracker(positionTracker);

        // Initialize Strategy Engine
        com.turkninja.engine.StrategyEngine strategyEngine = new com.turkninja.engine.StrategyEngine(
                mockWebSocketService, mockBinanceService, indicatorService, riskManager,
                positionTracker, orderBookService, telegramNotifier, null, params);

        // Disable async execution for backtesting (CRITICAL for correct results)
        strategyEngine.setAsyncExecution(false);

        // Create BacktestEngine
        BacktestEngine backtestEngine = new BacktestEngine(
                strategyEngine, mockBinanceService, realBinanceService, indicatorService);

        // Run Backtest
        BacktestReport report = backtestEngine.runBacktest(symbol, startDate, endDate, "5m");

        if (report == null) {
            return new ParameterPerformance(params, 0, 0, 0, 0, 0, 0);
        }

        return new ParameterPerformance(
                params, report.sharpeRatio, report.winRate / 100.0,
                report.maxDrawdownPercent / 100.0, report.getNetProfitPercent() / 100.0,
                report.profitFactor, report.totalTrades);
    }

    @Override
    public String getName() {
        return "Genetic Algorithm";
    }
}
