package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.BacktestEngine;
import com.turkninja.engine.IndicatorService;
import com.turkninja.engine.OrderBookService;
import com.turkninja.engine.StrategyEngine;
import com.turkninja.engine.optimizer.GridSearchOptimizer;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.MockFuturesBinanceService;
import com.turkninja.model.optimizer.OptimizationResult;
import com.turkninja.model.optimizer.ParameterSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CLI Runner for Parameter Optimization
 * 
 * Usage:
 * # Single symbol
 * mvn exec:java -Dexec.mainClass="com.turkninja.OptimizerRunner" \
 * -Dexec.args="grid ETHUSDT 2024-01-01 2024-12-01"
 * 
 * # All symbols
 * mvn exec:java -Dexec.mainClass="com.turkninja.OptimizerRunner" \
 * -Dexec.args="grid ALL 2024-01-01 2024-12-01"
 */
public class OptimizerRunner {

    private static final Logger logger = LoggerFactory.getLogger(OptimizerRunner.class);

    // All trading symbols
    private static final List<String> ALL_SYMBOLS = Arrays.asList(
            "ATOMUSDT", "BTCUSDT", "ETHUSDT", "DOGEUSDT", "SOLUSDT",
            "XRPUSDT", "ALGOUSDT", "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT");

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: OptimizerRunner <method> <symbol|ALL> <startDate> <endDate>");
            System.err.println("Example: OptimizerRunner grid ETHUSDT 2024-01-01 2024-12-01");
            System.err.println("Example: OptimizerRunner grid ALL 2024-01-01 2024-12-01");
            System.exit(1);
        }

        String method = args[0]; // "grid" or "genetic"
        String symbolArg = args[1]; // "ETHUSDT" or "ALL"
        String startDate = args[2]; // "2024-01-01"
        String endDate = args[3]; // "2024-12-01"

        // Determine symbols to optimize
        List<String> symbols = symbolArg.equalsIgnoreCase("ALL") ? ALL_SYMBOLS : Arrays.asList(symbolArg);

        logger.info("╔══════════════════════════════════════════════╗");
        logger.info("║   PARAMETER OPTIMIZATION RUNNER              ║");
        logger.info("╚══════════════════════════════════════════════╝");
        logger.info("");
        logger.info("Method:     {}", method);
        logger.info("Symbols:    {} ({})", symbols.size(), symbolArg);
        logger.info("Start Date: {}", startDate);
        logger.info("End Date:   {}", endDate);
        logger.info("");

        try {
            // Initialize services - SKIP initialization to prevent IP ban
            FuturesBinanceService futuresService = new FuturesBinanceService(true);
            IndicatorService indicatorService = new IndicatorService();

            // Define parameter space (same for all symbols initially)
            ParameterSpace parameterSpace = createParameterSpace();

            logger.info("Parameter Space:");
            logger.info("{}", parameterSpace);
            logger.info("");

            // Store all results
            List<OptimizationResult> allResults = new ArrayList<>();

            // Optimize each symbol
            for (int i = 0; i < symbols.size(); i++) {
                String symbol = symbols.get(i);

                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                logger.info("Optimizing Symbol {}/{}: {}", i + 1, symbols.size(), symbol);
                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                logger.info("");

                // Create fresh mock service for each backtest
                MockFuturesBinanceService mockService = new MockFuturesBinanceService(1000.0);

                BacktestEngine backtestEngine = new BacktestEngine(
                        null, mockService, futuresService, indicatorService);

                // Run optimization
                OptimizationResult result;

                if ("grid".equalsIgnoreCase(method)) {
                    // Initialize Grid Search Optimizer
                    GridSearchOptimizer optimizer = new GridSearchOptimizer(
                            indicatorService,
                            null, // webSocketService (not available in this context)
                            futuresService, // binanceService (using futuresService as the real Binance service)
                            null, // telegramNotifier (not available in this context)
                            null); // orderBookService (not available in this context)
                    result = optimizer.optimize(symbol, startDate, endDate, parameterSpace);
                } else if ("genetic".equalsIgnoreCase(method)) {
                    // Initialize Genetic Optimizer
                    com.turkninja.engine.optimizer.GeneticOptimizer optimizer = new com.turkninja.engine.optimizer.GeneticOptimizer(
                            indicatorService,
                            null, // webSocketService
                            futuresService,
                            null, // telegramNotifier
                            null); // orderBookService
                    result = optimizer.optimize(symbol, startDate, endDate, parameterSpace);
                } else {
                    logger.error("Unknown optimization method: {}. Use 'grid' or 'genetic'", method);
                    System.exit(1);
                    return;
                }

                allResults.add(result);

                // Print individual result
                System.out.println("\n" + result.getSummary());

                logger.info("");
            }

            // Print summary of all symbols
            printFinalSummary(allResults);

        } catch (Exception e) {
            logger.error("Optimization failed", e);
            System.exit(1);
        }
    }

    /**
     * Print final summary comparing all symbols
     */
    private static void printFinalSummary(List<OptimizationResult> results) {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           OPTIMIZATION SUMMARY - ALL SYMBOLS             ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println("");

        System.out.printf("%-12s | %-10s | %-10s | %-12s | %-12s%n",
                "Symbol", "Sharpe", "Win Rate", "Max DD", "Net Profit");
        System.out.println("─────────────┼────────────┼────────────┼──────────────┼──────────────");

        for (OptimizationResult result : results) {
            System.out.printf("%-12s | %10.2f | %9.1f%% | %11.2f%% | %11.2f%%%n",
                    result.getSymbol(),
                    result.getSharpeRatio(),
                    result.getWinRate() * 100,
                    result.getMaxDrawdown() * 100,
                    result.getNetProfit() * 100);
        }

        System.out.println("");

        // Find best performing symbol
        OptimizationResult best = results.stream()
                .max((a, b) -> Double.compare(a.getSharpeRatio(), b.getSharpeRatio()))
                .orElse(null);

        if (best != null) {
            System.out.println("🏆 Best Performer: " + best.getSymbol() +
                    " (Sharpe: " + String.format("%.2f", best.getSharpeRatio()) + ")");
        }

        System.out.println("");
    }

    /**
     * Create default parameter space for optimization
     * 
     * Expanded parameter space for Genetic Algorithm
     */
    private static ParameterSpace createParameterSpace() {
        return new ParameterSpace()
                // Risk Management Parameters - MINIMAL for fast optimization
                .addDiscrete("risk.stop_loss_percent", 0.015, 0.025)
                .addDiscrete("risk.take_profit_percent", 0.03, 0.05)
                .addDiscrete("risk.trailing_stop_percent", 0.005, 0.01)

                // RSI parameters - MINIMAL
                .addDiscrete("strategy.rsi.long.min", 30, 40)
                .addDiscrete("strategy.rsi.long.max", 65, 75)
                .addDiscrete("strategy.rsi.short.min", 25, 35)
                .addDiscrete("strategy.rsi.short.max", 55, 65)

                // ADX threshold - MINIMAL
                .addDiscrete("strategy.adx.min.strength", 20, 25)

                // EMA Slope - MINIMAL
                .addDiscrete("strategy.ema.slope.min.percent", 0.002, 0.004)
                .addDiscrete("strategy.ema.buffer.percent", 0.002, 0.004);
    }
}
