package com.turkninja.engine.optimizer;

import com.turkninja.model.optimizer.OptimizationResult;
import com.turkninja.model.optimizer.ParameterSet;
import com.turkninja.model.optimizer.ParameterSpace;

/**
 * Base interface for parameter optimization algorithms
 */
public interface ParameterOptimizer {

    /**
     * Optimize strategy parameters for a given symbol
     *
     * @param symbol         Symbol to optimize (e.g., "ETHUSDT")
     * @param startDate      Start date for backtest data (YYYY-MM-DD)
     * @param endDate        End date for backtest data (YYYY-MM-DD)
     * @param parameterSpace Parameter search space
     * @return Optimization result with best parameters
     */
    OptimizationResult optimize(
            String symbol,
            String startDate,
            String endDate,
            ParameterSpace parameterSpace);

    /**
     * Get optimizer name
     */
    String getName();
}
