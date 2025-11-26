package com.turkninja.model.optimizer;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stores the result of a parameter optimization run
 */
public class OptimizationResult {

    private String symbol;
    private String method; // "grid_search" or "genetic"
    private ZonedDateTime startTime;
    private ZonedDateTime endTime;
    private ParameterSet bestParameters;
    private double bestFitness; // Sharpe Ratio

    // Performance metrics
    private double sharpeRatio;
    private double winRate;
    private double maxDrawdown;
    private double netProfit;
    private double profitFactor;

    // Execution info
    private int totalBacktests;
    private Duration executionTime;

    // All tested combinations (for analysis)
    private List<ParameterPerformance> allResults;

    public OptimizationResult(String symbol, String method) {
        this.symbol = symbol;
        this.method = method;
        this.startTime = ZonedDateTime.now();
        this.allResults = new ArrayList<>();
    }

    public void complete() {
        this.endTime = ZonedDateTime.now();
        if (startTime != null && endTime != null) {
            this.executionTime = Duration.between(startTime, endTime);
        }
    }

    public void setBestResult(ParameterSet parameters, double sharpe, double winRate,
            double maxDD, double netProfit, double profitFactor) {
        this.bestParameters = parameters;
        this.bestFitness = sharpe;
        this.sharpeRatio = sharpe;
        this.winRate = winRate;
        this.maxDrawdown = maxDD;
        this.netProfit = netProfit;
        this.profitFactor = profitFactor;
    }

    public void addResult(ParameterPerformance performance) {
        allResults.add(performance);
        totalBacktests++;
    }

    /**
     * Get top N best parameter sets
     */
    public List<ParameterPerformance> getTopN(int n) {
        return allResults.stream()
                .sorted(Comparator.comparingDouble(ParameterPerformance::getFitness).reversed())
                .limit(n)
                .toList();
    }

    /**
     * Get summary report as string
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════╗\n");
        sb.append("║     OPTIMIZATION RESULT                      ║\n");
        sb.append("╚══════════════════════════════════════════════╝\n\n");

        sb.append(String.format("Symbol:          %s\n", symbol));
        sb.append(String.format("Method:          %s\n", method));
        sb.append(String.format("Total Backtests: %d\n", totalBacktests));
        sb.append(String.format("Execution Time:  %s\n", formatDuration(executionTime)));
        sb.append("\n");

        sb.append("BEST PARAMETERS:\n");
        sb.append("────────────────────────────────────────────────\n");
        if (bestParameters != null) {
            bestParameters.getAll().forEach((k, v) -> sb.append(String.format("  %-20s: %.2f\n", k, v)));
        }
        sb.append("\n");

        sb.append("PERFORMANCE METRICS:\n");
        sb.append("────────────────────────────────────────────────\n");
        sb.append(String.format("  Sharpe Ratio:    %.2f\n", sharpeRatio));
        sb.append(String.format("  Win Rate:        %.1f%%\n", winRate * 100));
        sb.append(String.format("  Max Drawdown:    %.2f%%\n", maxDrawdown * 100));
        sb.append(String.format("  Net Profit:      %.2f%%\n", netProfit * 100));
        sb.append(String.format("  Profit Factor:   %.2f\n", profitFactor));
        sb.append("\n");

        return sb.toString();
    }

    private String formatDuration(Duration duration) {
        if (duration == null)
            return "N/A";
        long hours = duration.toHours();
        long minutes = duration.toMinutes() % 60;
        long seconds = duration.getSeconds() % 60;
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }

    // Getters and Setters
    public String getSymbol() {
        return symbol;
    }

    public String getMethod() {
        return method;
    }

    public ParameterSet getBestParameters() {
        return bestParameters;
    }

    public double getBestFitness() {
        return bestFitness;
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

    public int getTotalBacktests() {
        return totalBacktests;
    }

    public Duration getExecutionTime() {
        return executionTime;
    }

    public List<ParameterPerformance> getAllResults() {
        return new ArrayList<>(allResults);
    }
}
