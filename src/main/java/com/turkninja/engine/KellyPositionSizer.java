package com.turkninja.engine;

import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Kelly Criterion Position Sizer
 * Dynamically calculates optimal position size based on historical win rate and
 * profit ratios.
 * 
 * Formula: f* = (bp - q) / b
 * Where:
 * f* = fraction of capital to bet (Kelly fraction)
 * b = ratio of average win to average loss
 * p = probability of winning (win rate)
 * q = probability of losing (1 - p)
 * 
 * Safety Features:
 * - Uses fractional Kelly (default 25%) to reduce volatility
 * - Requires minimum trade history before activation
 * - Caps maximum position size
 * - Fallback to fixed sizing if conditions not met
 */
public class KellyPositionSizer {

    private static final Logger logger = LoggerFactory.getLogger(KellyPositionSizer.class);

    // Trade history tracking
    private final List<TradeResult> tradeHistory = new ArrayList<>();

    // Configuration
    private final boolean enabled;
    private final double safetyMultiplier; // Fraction of full Kelly (0.25 = 25%)
    private final int minTrades; // Minimum trades before Kelly activates
    private final boolean fallbackToFixed;
    private final double maxPositionPercent; // Maximum position size cap

    /**
     * Trade result data class
     */
    public static class TradeResult {
        public final boolean isWin;
        public final double profitRatio; // Profit or loss as ratio of position size

        public TradeResult(boolean isWin, double profitRatio) {
            this.isWin = isWin;
            this.profitRatio = profitRatio;
        }
    }

    public KellyPositionSizer() {
        this.enabled = Boolean.parseBoolean(Config.get("strategy.position.kelly.enabled", "false"));
        this.safetyMultiplier = Config.getDouble("strategy.position.kelly.safety_multiplier", 0.25);
        this.minTrades = Config.getInt("strategy.position.kelly.min_trades", 20);
        this.fallbackToFixed = Boolean.parseBoolean(Config.get("strategy.position.kelly.fallback_to_fixed", "true"));
        this.maxPositionPercent = Config.getDouble("strategy.position.max_percent", 0.25);

        logger.info("KellyPositionSizer initialized: enabled={}, safety={}, minTrades={}",
                enabled, safetyMultiplier, minTrades);
    }

    /**
     * Calculate optimal position size using Kelly Criterion
     * 
     * @param symbol           Trading symbol (for logging)
     * @param availableBalance Current available balance
     * @return Optimal position size in USDT
     */
    public double getPositionSize(String symbol, double availableBalance) {
        if (!enabled) {
            return fallbackPositionSize(availableBalance, "Kelly disabled");
        }

        if (!hasSufficientHistory()) {
            return fallbackPositionSize(availableBalance,
                    String.format("Insufficient history (%d/%d trades)", tradeHistory.size(), minTrades));
        }

        // Calculate Kelly fraction
        double kellyFraction = calculateKellyFraction();

        // Apply safety multiplier (fractional Kelly)
        double adjustedFraction = kellyFraction * safetyMultiplier;

        // Cap at maximum position percent
        adjustedFraction = Math.min(adjustedFraction, maxPositionPercent);

        // Ensure positive and reasonable
        if (adjustedFraction <= 0 || adjustedFraction > maxPositionPercent) {
            logger.warn("📊 {} Kelly fraction out of bounds ({:.2f}%), using fixed sizing",
                    symbol, adjustedFraction * 100);
            return fallbackPositionSize(availableBalance, "Kelly fraction out of bounds");
        }

        double positionSize = availableBalance * adjustedFraction;

        logger.info("📊 {} Kelly sizing: winRate={:.1f}%, Kelly={:.2f}%, Adjusted={:.2f}%, Size=${:.2f}",
                symbol, getWinRate() * 100, kellyFraction * 100, adjustedFraction * 100, positionSize);

        return positionSize;
    }

    /**
     * Calculate Kelly fraction: f* = (bp - q) / b
     */
    private double calculateKellyFraction() {
        double winRate = getWinRate();
        double lossRate = 1.0 - winRate;

        // Calculate average win/loss ratio
        double avgWin = calculateAverageWin();
        double avgLoss = Math.abs(calculateAverageLoss());

        if (avgLoss == 0) {
            logger.warn("⚠️ Average loss is zero, using fallback");
            return maxPositionPercent;
        }

        double b = avgWin / avgLoss; // Win/loss ratio

        // Kelly formula: f* = (bp - q) / b
        double kelly = (b * winRate - lossRate) / b;

        return kelly;
    }

    /**
     * Get current win rate (percentage)
     */
    public double getWinRate() {
        if (tradeHistory.isEmpty()) {
            return 0.5; // Neutral assumption
        }

        long wins = tradeHistory.stream().filter(t -> t.isWin).count();
        return (double) wins / tradeHistory.size();
    }

    /**
     * Calculate average win ratio
     */
    private double calculateAverageWin() {
        List<Double> wins = tradeHistory.stream()
                .filter(t -> t.isWin)
                .map(t -> t.profitRatio)
                .toList();

        if (wins.isEmpty()) {
            return 0.01; // 1% default
        }

        return wins.stream().mapToDouble(Double::doubleValue).average().orElse(0.01);
    }

    /**
     * Calculate average loss ratio (returns negative value)
     */
    private double calculateAverageLoss() {
        List<Double> losses = tradeHistory.stream()
                .filter(t -> !t.isWin)
                .map(t -> t.profitRatio)
                .toList();

        if (losses.isEmpty()) {
            return -0.01; // -1% default
        }

        return losses.stream().mapToDouble(Double::doubleValue).average().orElse(-0.01);
    }

    /**
     * Check if sufficient trade history exists
     */
    public boolean hasSufficientHistory() {
        return tradeHistory.size() >= minTrades;
    }

    /**
     * Record a completed trade
     * 
     * @param isWin       Whether trade was profitable
     * @param profitRatio Profit/loss as ratio (e.g., 0.02 for 2% profit, -0.01 for
     *                    1% loss)
     */
    public void recordTrade(boolean isWin, double profitRatio) {
        TradeResult result = new TradeResult(isWin, profitRatio);
        tradeHistory.add(result);

        // Keep only last 100 trades to avoid memory issues
        if (tradeHistory.size() > 100) {
            tradeHistory.remove(0);
        }

        logger.debug("📝 Trade recorded: win={}, ratio={:.2f}%, Total trades: {}, Win rate: {:.1f}%",
                isWin, profitRatio * 100, tradeHistory.size(), getWinRate() * 100);
    }

    /**
     * Fallback to fixed position sizing
     */
    private double fallbackPositionSize(double availableBalance, String reason) {
        if (fallbackToFixed) {
            double positionSize = availableBalance * maxPositionPercent;
            logger.debug("📊 Using fixed sizing ({:.0f}%): {} - ${:.2f}",
                    maxPositionPercent * 100, reason, positionSize);
            return positionSize;
        } else {
            logger.warn("⚠️ Kelly sizing failed and fallback disabled: {}", reason);
            return 0.0;
        }
    }

    /**
     * Get statistics summary
     */
    public String getStatsSummary() {
        if (tradeHistory.isEmpty()) {
            return "No trades recorded";
        }

        return String.format("Trades: %d, Win Rate: %.1f%%, Avg Win: %.2f%%, Avg Loss: %.2f%%",
                tradeHistory.size(),
                getWinRate() * 100,
                calculateAverageWin() * 100,
                calculateAverageLoss() * 100);
    }

    /**
     * Check if Kelly sizing is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Get trade history (for testing/analysis)
     */
    public List<TradeResult> getTradeHistory() {
        return new ArrayList<>(tradeHistory);
    }

    /**
     * Clear trade history (for testing)
     */
    public void clearHistory() {
        tradeHistory.clear();
        logger.info("Trade history cleared");
    }

    /**
     * Load historical trade metrics from external storage (InfluxDB/Redis)
     * This preserves Kelly calculations across bot restarts
     * 
     * @param winRate      Historical win rate (0.0-1.0)
     * @param avgWinRatio  Average profit ratio on winning trades
     * @param avgLossRatio Average loss ratio on losing trades (negative)
     * @param tradeCount   Total number of historical trades
     */
    public void loadHistoryFromMetrics(double winRate, double avgWinRatio, double avgLossRatio, int tradeCount) {
        if (tradeCount < minTrades) {
            logger.info("📊 Kelly: Insufficient historical trades ({}/{}), starting fresh", tradeCount, minTrades);
            return;
        }

        // Reconstruct approximate trade history from metrics
        int wins = (int) Math.round(tradeCount * winRate);
        int losses = tradeCount - wins;

        // Add winning trades
        for (int i = 0; i < wins; i++) {
            tradeHistory.add(new TradeResult(true, avgWinRatio));
        }

        // Add losing trades
        for (int i = 0; i < losses; i++) {
            tradeHistory.add(new TradeResult(false, avgLossRatio));
        }

        logger.info(
                "📊 Kelly history loaded from metrics: {} trades, {:.1f}% win rate, Avg Win: {:.2f}%, Avg Loss: {:.2f}%",
                tradeCount, winRate * 100, avgWinRatio * 100, avgLossRatio * 100);
    }

    /**
     * Get current metrics for persistence
     * 
     * @return Map containing winRate, avgWinRatio, avgLossRatio, tradeCount
     */
    public java.util.Map<String, Double> getMetricsForPersistence() {
        java.util.Map<String, Double> metrics = new java.util.HashMap<>();
        metrics.put("winRate", getWinRate());
        metrics.put("avgWinRatio", calculateAverageWin());
        metrics.put("avgLossRatio", calculateAverageLoss());
        metrics.put("tradeCount", (double) tradeHistory.size());
        return metrics;
    }
}
