package com.turkninja.model.optimizer;

/**
 * Stores performance metrics for a single parameter set
 */
public class ParameterPerformance {

    private final ParameterSet parameters;
    private final double fitness; // Primary metric (Sharpe Ratio)
    private final double sharpeRatio;
    private final double winRate;
    private final double maxDrawdown;
    private final double netProfit;
    private final double profitFactor;
    private final int totalTrades;

    public ParameterPerformance(ParameterSet parameters,
            double sharpeRatio,
            double winRate,
            double maxDrawdown,
            double netProfit,
            double profitFactor,
            int totalTrades) {
        this.parameters = parameters;
        this.sharpeRatio = sharpeRatio;
        this.winRate = winRate;
        this.maxDrawdown = maxDrawdown;
        this.netProfit = netProfit;
        this.profitFactor = profitFactor;
        this.totalTrades = totalTrades;

        // Fitness = Sharpe Ratio (primary optimization metric)
        this.fitness = sharpeRatio;
    }

    public ParameterSet getParameters() {
        return parameters;
    }

    public double getFitness() {
        return fitness;
    }

    public double getSharpeRatio() {
        return sharpeRatio;
    }

    public double getWinRate() {
        return winRate;
    }

    public double getMaxDrawdown() {
        return maxDrawdown;
    }

    public double getNetProfit() {
        return netProfit;
    }

    public double getProfitFactor() {
        return profitFactor;
    }

    public int getTotalTrades() {
        return totalTrades;
    }

    @Override
    public String toString() {
        return String.format("ParameterPerformance{fitness=%.2f, sharpe=%.2f, winRate=%.1f%%, params=%s}",
                fitness, sharpeRatio, winRate * 100, parameters);
    }
}
