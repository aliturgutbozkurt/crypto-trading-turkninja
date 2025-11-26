package com.turkninja.engine;

import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Performance Metrics Tracker
 * 
 * Tracks real-time trading performance metrics using rolling windows.
 * Provides insights into strategy performance without requiring backtesting.
 * 
 * Metrics tracked:
 * - Rolling win rate (last N trades)
 * - Average profit/loss
 * - Sharpe ratio (risk-adjusted return)
 * - Max drawdown
 * - Trade frequency
 * - Current streak (wins/losses)
 */
public class PerformanceMetrics {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceMetrics.class);

    // Configuration
    private final boolean enabled;
    private final int windowSize; // Number of trades for rolling calculations

    // Trade history (rolling window)
    private final Queue<TradeMetric> tradeWindow = new LinkedList<>();

    // Equity tracking
    private double initialBalance = 0.0;
    private double currentBalance = 0.0;
    private double peakBalance = 0.0; // Peak balance (for drawdown calculation)

    // Real-time metrics
    private int totalTrades = 0;
    private int winningTrades = 0;
    private int losingTrades = 0;
    private double totalProfit = 0.0;
    private double totalLoss = 0.0;
    private double maxDrawdown = 0.0;
    private double maxDrawdownPercent = 0.0;

    // Streaks
    private int currentStreak = 0; // Positive for wins, negative for losses
    private int maxWinStreak = 0;
    private int maxLossStreak = 0;

    // Performance start time
    private final ZonedDateTime startTime;

    /**
     * Individual trade metric
     */
    private static class TradeMetric {
        ZonedDateTime timestamp;
        String symbol;
        String side;
        double profit;
        double profitPercent;
        boolean isWin;

        TradeMetric(String symbol, String side, double profit, double profitPercent) {
            this.timestamp = ZonedDateTime.now();
            this.symbol = symbol;
            this.side = side;
            this.profit = profit;
            this.profitPercent = profitPercent;
            this.isWin = profit > 0;
        }
    }

    public PerformanceMetrics(double initialBalance) {
        this.enabled = Boolean.parseBoolean(Config.get("monitoring.metrics.enabled", "true"));
        this.windowSize = Config.getInt("monitoring.metrics.window_size", 50);
        this.initialBalance = initialBalance;
        this.currentBalance = initialBalance;
        this.peakBalance = initialBalance;
        this.startTime = ZonedDateTime.now();

        if (enabled) {
            logger.info("✅ PerformanceMetrics initialized: windowSize={}, initialBalance=${}",
                    windowSize, initialBalance);
        }
    }

    /**
     * Record a completed trade
     */
    public void recordTrade(String symbol, String side, double profit, double profitPercent, double newBalance) {
        if (!enabled)
            return;

        TradeMetric metric = new TradeMetric(symbol, side, profit, profitPercent);

        // Add to rolling window
        tradeWindow.offer(metric);
        if (tradeWindow.size() > windowSize) {
            tradeWindow.poll(); // Remove oldest
        }

        // Update totals
        totalTrades++;
        if (metric.isWin) {
            winningTrades++;
            totalProfit += profit;

            // Update win streak
            if (currentStreak >= 0) {
                currentStreak++;
            } else {
                currentStreak = 1; // Reset
            }
            maxWinStreak = Math.max(maxWinStreak, currentStreak);

        } else {
            losingTrades++;
            totalLoss += Math.abs(profit);

            // Update loss streak
            if (currentStreak <= 0) {
                currentStreak--;
            } else {
                currentStreak = -1; // Reset
            }
            maxLossStreak = Math.max(maxLossStreak, Math.abs(currentStreak));
        }

        // Update balance and drawdown
        currentBalance = newBalance;
        if (currentBalance > peakBalance) {
            peakBalance = currentBalance;
        }

        double drawdown = peakBalance - currentBalance;
        if (drawdown > maxDrawdown) {
            maxDrawdown = drawdown;
            maxDrawdownPercent = (drawdown / peakBalance) * 100;
        }

        // Log significant events
        if (metric.isWin && Math.random() < 0.1) { // 10% of wins
            logger.info("📊 Performance: Win Rate={:.1f}%, Net PnL=${:.2f}, Max DD={:.2f}%",
                    getWinRate(), getNetProfit(), maxDrawdownPercent);
        }
    }

    /**
     * Get rolling win rate (percentage)
     */
    public double getWinRate() {
        if (tradeWindow.isEmpty())
            return 0.0;

        long wins = tradeWindow.stream().filter(t -> t.isWin).count();
        return (double) wins / tradeWindow.size() * 100;
    }

    /**
     * Get overall win rate (all trades)
     */
    public double getOverallWinRate() {
        if (totalTrades == 0)
            return 0.0;
        return (double) winningTrades / totalTrades * 100;
    }

    /**
     * Get rolling average profit (only winning trades)
     */
    public double getAverageWin() {
        List<TradeMetric> wins = tradeWindow.stream().filter(t -> t.isWin).toList();
        if (wins.isEmpty())
            return 0.0;
        return wins.stream().mapToDouble(t -> t.profit).average().orElse(0.0);
    }

    /**
     * Get rolling average loss (only losing trades)
     */
    public double getAverageLoss() {
        List<TradeMetric> losses = tradeWindow.stream().filter(t -> !t.isWin).toList();
        if (losses.isEmpty())
            return 0.0;
        return Math.abs(losses.stream().mapToDouble(t -> t.profit).average().orElse(0.0));
    }

    /**
     * Get profit factor (total profit / total loss)
     */
    public double getProfitFactor() {
        if (totalLoss == 0)
            return 0.0;
        return totalProfit / totalLoss;
    }

    /**
     * Get expectancy (average expected profit per trade)
     */
    public double getExpectancy() {
        if (totalTrades == 0)
            return 0.0;
        double winRate = getOverallWinRate() / 100.0;
        double avgWin = totalProfit / Math.max(1, winningTrades);
        double avgLoss = totalLoss / Math.max(1, losingTrades);
        return (winRate * avgWin) - ((1 - winRate) * avgLoss);
    }

    /**
     * Get net profit
     */
    public double getNetProfit() {
        return currentBalance - initialBalance;
    }

    /**
     * Get net profit percentage
     */
    public double getNetProfitPercent() {
        if (initialBalance == 0)
            return 0.0;
        return (getNetProfit() / initialBalance) * 100;
    }

    /**
     * Calculate Sharpe Ratio (simplified)
     * Based on trade returns, not daily returns
     */
    public double getSharpeRatio() {
        if (tradeWindow.size() < 2)
            return 0.0;

        List<Double> returns = tradeWindow.stream()
                .map(t -> t.profitPercent / 100.0)
                .toList();

        double avgReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = returns.stream()
                .mapToDouble(r -> Math.pow(r - avgReturn, 2))
                .average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        if (stdDev == 0)
            return 0.0;
        return avgReturn / stdDev;
    }

    /**
     * Get trade frequency (trades per day)
     */
    public double getTradeFrequency() {
        if (totalTrades == 0)
            return 0.0;

        long daysSinceStart = java.time.Duration.between(startTime, ZonedDateTime.now()).toDays();
        if (daysSinceStart == 0)
            return totalTrades; // Same day

        return (double) totalTrades / daysSinceStart;
    }

    /**
     * Get formatted summary
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("═".repeat(60)).append("\n");
        sb.append("  PERFORMANCE METRICS (Real-Time)\n");
        sb.append("═".repeat(60)).append("\n");
        sb.append(String.format("Total Trades:    %d (Won: %d, Lost: %d)\n", totalTrades, winningTrades, losingTrades));
        sb.append(String.format("Win Rate:        %.1f%% (Rolling: %.1f%% over %d trades)\n",
                getOverallWinRate(), getWinRate(), Math.min(windowSize, tradeWindow.size())));
        sb.append(String.format("Net Profit:      $%.2f (%.2f%%)\n", getNetProfit(), getNetProfitPercent()));
        sb.append(String.format("Profit Factor:   %.2f\n", getProfitFactor()));
        sb.append(String.format("Expectancy:      $%.2f per trade\n", getExpectancy()));
        sb.append(String.format("Max Drawdown:    $%.2f (%.2f%%)\n", maxDrawdown, maxDrawdownPercent));
        sb.append(String.format("Sharpe Ratio:    %.2f\n", getSharpeRatio()));
        sb.append(String.format("Avg Win:         $%.2f\n", getAverageWin()));
        sb.append(String.format("Avg Loss:        $%.2f\n", getAverageLoss()));
        sb.append(String.format("Current Streak:  %s\n", getStreakString()));
        sb.append(String.format("Trade Frequency: %.1f per day\n", getTradeFrequency()));
        sb.append("═".repeat(60)).append("\n");
        return sb.toString();
    }

    /**
     * Get streak description
     */
    private String getStreakString() {
        if (currentStreak > 0) {
            return currentStreak + " wins (Max: " + maxWinStreak + ")";
        } else if (currentStreak < 0) {
            return Math.abs(currentStreak) + " losses (Max: " + maxLossStreak + ")";
        } else {
            return "None";
        }
    }

    /**
     * Get metrics as JSON (for API/monitoring export)
     */
    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append(String.format("  \"totalTrades\": %d,\n", totalTrades));
        json.append(String.format("  \"winRate\": %.2f,\n", getOverallWinRate()));
        json.append(String.format("  \"rollingWinRate\": %.2f,\n", getWinRate()));
        json.append(String.format("  \"netProfit\": %.2f,\n", getNetProfit()));
        json.append(String.format("  \"profitFactor\": %.2f,\n", getProfitFactor()));
        json.append(String.format("  \"expectancy\": %.2f,\n", getExpectancy()));
        json.append(String.format("  \"maxDrawdown\": %.2f,\n", maxDrawdownPercent));
        json.append(String.format("  \"sharpeRatio\": %.2f,\n", getSharpeRatio()));
        json.append(String.format("  \"currentStreak\": %d,\n", currentStreak));
        json.append(String.format("  \"tradeFrequency\": %.2f\n", getTradeFrequency()));
        json.append("}");
        return json.toString();
    }

    /**
     * Check if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get total trades count
     */
    public int getTotalTrades() {
        return totalTrades;
    }
}
