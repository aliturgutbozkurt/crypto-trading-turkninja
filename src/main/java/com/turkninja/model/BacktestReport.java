package com.turkninja.model;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Backtest Report - Performance Metrics and Trade Log
 * 
 * Contains comprehensive results from a backtest run including:
 * - Financial metrics (PnL, win rate, profit factor)
 * - Risk metrics (max drawdown, Sharpe ratio)
 * - Trade-by-trade log
 */
public class BacktestReport {

    // Basic Information
    public String symbol;
    public String timeframe;
    public ZonedDateTime startTime;
    public ZonedDateTime endTime;
    public double initialBalance;
    public double finalBalance;

    // Trade Statistics
    public int totalTrades = 0;
    public int winningTrades = 0;
    public int losingTrades = 0;
    public double winRate = 0.0; // Percentage

    // Financial Metrics
    public double totalProfit = 0.0;
    public double totalLoss = 0.0;
    public double netProfit = 0.0;
    public double profitFactor = 0.0; // Total profit / Total loss
    public double averageWin = 0.0;
    public double averageLoss = 0.0;
    public double largestWin = 0.0;
    public double largestLoss = 0.0;

    // Risk Metrics
    public double maxDrawdown = 0.0; // Maximum peak-to-trough decline
    public double maxDrawdownPercent = 0.0;
    public double sharpeRatio = 0.0; // Risk-adjusted return
    public double sortinoRatio = 0.0; // Risk-adjusted return (downside only)

    // Additional Metrics
    public double expectancy = 0.0; // Average expected profit per trade
    public double averageHoldingTimeDays = 0.0;
    public int consecutiveWins = 0;
    public int consecutiveLosses = 0;
    public int maxConsecutiveWins = 0;
    public int maxConsecutiveLosses = 0;

    // Trade Log
    public List<TradeEntry> trades = new ArrayList<>();

    // Equity Curve
    public List<EquityPoint> equityCurve = new ArrayList<>();

    /**
     * Individual trade entry
     */
    public static class TradeEntry {
        public ZonedDateTime entryTime;
        public ZonedDateTime exitTime;
        public String side; // "BUY" or "SELL"
        public double entryPrice;
        public double exitPrice;
        public double quantity;
        public double pnl;
        public double pnlPercent;
        public String exitReason; // "TAKE_PROFIT", "STOP_LOSS", "TRAILING_STOP"
        public double commission;

        public TradeEntry(ZonedDateTime entryTime, String side, double entryPrice, double quantity) {
            this.entryTime = entryTime;
            this.side = side;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
        }
    }

    /**
     * Equity curve point (balance over time)
     */
    public static class EquityPoint {
        public ZonedDateTime timestamp;
        public double balance;
        public double drawdownPercent;

        public EquityPoint(ZonedDateTime timestamp, double balance, double drawdownPercent) {
            this.timestamp = timestamp;
            this.balance = balance;
            this.drawdownPercent = drawdownPercent;
        }
    }

    /**
     * Calculate all metrics from trade log
     */
    public void calculateMetrics() {
        if (trades.isEmpty()) {
            return;
        }

        // Basic counts
        totalTrades = trades.size();
        winningTrades = (int) trades.stream().filter(t -> t.pnl > 0).count();
        losingTrades = (int) trades.stream().filter(t -> t.pnl < 0).count();
        winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;

        // Financial metrics
        totalProfit = trades.stream().filter(t -> t.pnl > 0).mapToDouble(t -> t.pnl).sum();
        totalLoss = Math.abs(trades.stream().filter(t -> t.pnl < 0).mapToDouble(t -> t.pnl).sum());
        netProfit = totalProfit - totalLoss;
        finalBalance = initialBalance + netProfit;
        profitFactor = totalLoss > 0 ? totalProfit / totalLoss : 0;

        averageWin = winningTrades > 0 ? totalProfit / winningTrades : 0;
        averageLoss = losingTrades > 0 ? totalLoss / losingTrades : 0;

        largestWin = trades.stream().mapToDouble(t -> t.pnl).max().orElse(0);
        largestLoss = trades.stream().mapToDouble(t -> t.pnl).min().orElse(0);

        // Expectancy
        expectancy = (winRate / 100.0 * averageWin) - ((1 - winRate / 100.0) * averageLoss);

        // Consecutive wins/losses
        int currentStreak = 0;
        boolean lastWasWin = false;
        for (TradeEntry trade : trades) {
            boolean isWin = trade.pnl > 0;
            if (isWin == lastWasWin) {
                currentStreak++;
            } else {
                if (lastWasWin) {
                    maxConsecutiveWins = Math.max(maxConsecutiveWins, currentStreak);
                } else {
                    maxConsecutiveLosses = Math.max(maxConsecutiveLosses, currentStreak);
                }
                currentStreak = 1;
                lastWasWin = isWin;
            }
        }

        // Calculate max drawdown from equity curve
        if (!equityCurve.isEmpty()) {
            double peak = initialBalance;
            for (EquityPoint point : equityCurve) {
                if (point.balance > peak) {
                    peak = point.balance;
                }
                double drawdown = peak - point.balance;
                if (drawdown > maxDrawdown) {
                    maxDrawdown = drawdown;
                    maxDrawdownPercent = (drawdown / peak) * 100;
                }
            }
        }

        // Sharpe Ratio (simplified - assumes daily returns)
        if (!equityCurve.isEmpty() && equityCurve.size() > 1) {
            List<Double> returns = new ArrayList<>();
            for (int i = 1; i < equityCurve.size(); i++) {
                double ret = (equityCurve.get(i).balance - equityCurve.get(i - 1).balance)
                        / equityCurve.get(i - 1).balance;
                returns.add(ret);
            }

            double avgReturn = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = returns.stream()
                    .mapToDouble(r -> Math.pow(r - avgReturn, 2))
                    .average().orElse(0);
            double stdDev = Math.sqrt(variance);

            // Annualized Sharpe Ratio (assuming 252 trading days)
            sharpeRatio = stdDev > 0 ? (avgReturn / stdDev) * Math.sqrt(252) : 0;
        }

        // Average holding time
        long totalHoldingTimeMillis = 0;
        for (TradeEntry trade : trades) {
            if (trade.exitTime != null) {
                long holdingTime = java.time.Duration.between(trade.entryTime, trade.exitTime).toMillis();
                totalHoldingTimeMillis += holdingTime;
            }
        }
        averageHoldingTimeDays = totalTrades > 0
                ? (double) totalHoldingTimeMillis / totalTrades / (1000 * 60 * 60 * 24)
                : 0;
    }

    /**
     * Get formatted summary string
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(60)).append("\n");
        sb.append("  BACKTEST REPORT\n");
        sb.append("=".repeat(60)).append("\n");
        sb.append(String.format("Symbol: %s | Timeframe: %s\n", symbol, timeframe));
        sb.append(String.format("Period: %s to %s\n", startTime, endTime));
        sb.append("\n");

        sb.append("FINANCIAL PERFORMANCE\n");
        sb.append("-".repeat(60)).append("\n");
        sb.append(String.format("Initial Balance: $%.2f\n", initialBalance));
        sb.append(String.format("Final Balance:   $%.2f\n", finalBalance));
        sb.append(String.format("Net Profit:      $%.2f (%.2f%%)\n", netProfit, (netProfit / initialBalance) * 100));
        sb.append(String.format("Total Trades:    %d (Won: %d, Lost: %d)\n", totalTrades, winningTrades, losingTrades));
        sb.append(String.format("Win Rate:        %.1f%%\n", winRate));
        sb.append(String.format("Profit Factor:   %.2f\n", profitFactor));
        sb.append(String.format("Expectancy:      $%.2f per trade\n", expectancy));
        sb.append("\n");

        sb.append("RISK METRICS\n");
        sb.append("-".repeat(60)).append("\n");
        sb.append(String.format("Max Drawdown:    $%.2f (%.2f%%)\n", maxDrawdown, maxDrawdownPercent));
        sb.append(String.format("Sharpe Ratio:    %.2f\n", sharpeRatio));
        sb.append(String.format("Largest Win:     $%.2f\n", largestWin));
        sb.append(String.format("Largest Loss:    $%.2f\n", largestLoss));
        sb.append(String.format("Avg Win:         $%.2f\n", averageWin));
        sb.append(String.format("Avg Loss:        $%.2f\n", averageLoss));
        sb.append("\n");

        sb.append("TRADE STATISTICS\n");
        sb.append("-".repeat(60)).append("\n");
        sb.append(String.format("Avg Holding Time: %.2f days\n", averageHoldingTimeDays));
        sb.append(String.format("Max Consecutive Wins:   %d\n", maxConsecutiveWins));
        sb.append(String.format("Max Consecutive Losses: %d\n", maxConsecutiveLosses));
        sb.append("=".repeat(60)).append("\n");

        return sb.toString();
    }

    /**
     * Get net profit percentage
     */
    public double getNetProfitPercent() {
        if (initialBalance == 0)
            return 0.0;
        return (netProfit / initialBalance) * 100;
    }

    /**
     * Export to JSON format (simplified)
     */
    public String toJson() {
        // Simple JSON serialization (in production, use Jackson or Gson)
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append(String.format("  \"symbol\": \"%s\",\n", symbol));
        json.append(String.format("  \"timeframe\": \"%s\",\n", timeframe));
        json.append(String.format("  \"totalTrades\": %d,\n", totalTrades));
        json.append(String.format("  \"winRate\": %.2f,\n", winRate));
        json.append(String.format("  \"netProfit\": %.2f,\n", netProfit));
        json.append(String.format("  \"profitFactor\": %.2f,\n", profitFactor));
        json.append(String.format("  \"maxDrawdown\": %.2f,\n", maxDrawdownPercent));
        json.append(String.format("  \"sharpeRatio\": %.2f\n", sharpeRatio));
        json.append("}");
        return json.toString();
    }
}
