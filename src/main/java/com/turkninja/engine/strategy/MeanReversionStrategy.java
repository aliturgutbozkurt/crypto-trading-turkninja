package com.turkninja.engine.strategy;

import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.util.Map;

/**
 * Mean Reversion Strategy
 * 
 * Designed for ranging/sideways markets. Enters positions when price deviates
 * significantly from the mean (Bollinger Bands) and expects reversion back.
 * 
 * Entry Logic (LONG):
 * - ADX < 15 (ranging market)
 * - Price touches/breaches lower Bollinger Band
 * - RSI < 30 (oversold)
 * - Optional: RSI bullish divergence
 * 
 * Entry Logic (SHORT):
 * - ADX < 15 (ranging market)
 * - Price touches/breaches upper Bollinger Band
 * - RSI > 70 (overbought)
 * - Optional: RSI bearish divergence
 * 
 * Exit Logic:
 * - Target: Middle Bollinger Band (mean)
 * - Stop Loss: Beyond outer band (1.5x ATR)
 * - Time Stop: Exit after N candles if no movement
 */
public class MeanReversionStrategy {

    private static final Logger logger = LoggerFactory.getLogger(MeanReversionStrategy.class);

    // Configuration
    private final boolean enabled;
    private final double adxMax; // Max ADX for ranging (15)
    private final int bbPeriod; // Bollinger Bands period (20)
    private final double bbStdDev; // BB standard deviations (2.0)
    private final double rsiOversold; // RSI oversold level (30)
    private final double rsiOverbought; // RSI overbought level (70)
    private final boolean divergenceEnabled; // Use RSI divergence
    private final double bbTouchTolerance; // How close to BB to trigger (0.01 = 1%)

    public MeanReversionStrategy() {
        this.enabled = Config.getBoolean("range.enabled", true);
        this.adxMax = Config.getDouble("regime.adx.range.max", 15.0);
        this.bbPeriod = Config.getInt("range.bb.period", 20);
        this.bbStdDev = Config.getDouble("range.bb.stddev", 2.0);
        this.rsiOversold = Config.getDouble("range.rsi.oversold", 30.0);
        this.rsiOverbought = Config.getDouble("range.rsi.overbought", 70.0);
        this.divergenceEnabled = Config.getBoolean("range.divergence.enabled", false);
        this.bbTouchTolerance = Config.getDouble("range.bb.touch.tolerance", 0.01);

        logger.info("✅ Mean Reversion Strategy initialized: ADX<{}, BB({},{}σ), RSI[{}/{}], Div={}",
                adxMax, bbPeriod, bbStdDev, rsiOversold, rsiOverbought, divergenceEnabled);
    }

    /**
     * Check if mean reversion LONG entry is valid
     * 
     * @param symbol       Symbol name
     * @param series       Bar series
     * @param indicators   Pre-calculated indicators
     * @param currentPrice Current price
     * @return true if LONG signal confirmed
     */
    public boolean checkLongEntry(String symbol, BarSeries series,
            Map<String, Double> indicators, double currentPrice) {

        if (!enabled) {
            return false;
        }

        // 1. ADX check - must be ranging
        double adx = indicators.getOrDefault("ADX", 100.0);
        if (adx >= adxMax) {
            logger.debug("⏸️ {} Mean Reversion LONG filtered - ADX too high ({} >= {})",
                    symbol, adx, adxMax);
            return false;
        }

        // 2. Bollinger Bands check - price near/below lower band
        double bbLower = indicators.getOrDefault("BB_LOWER", 0.0);
        double bbMiddle = indicators.getOrDefault("BB_MIDDLE", currentPrice);
        double bbUpper = indicators.getOrDefault("BB_UPPER", currentPrice * 2);

        if (bbLower == 0.0) {
            logger.debug("⏸️ {} Mean Reversion LONG filtered - BB not available", symbol);
            return false;
        }

        // Price must be within tolerance of lower band
        double lowerBandThreshold = bbLower * (1 + bbTouchTolerance);
        if (currentPrice > lowerBandThreshold) {
            logger.debug("⏸️ {} Mean Reversion LONG filtered - Price {:.2f} > Lower BB {:.2f} (+{}%)",
                    symbol, currentPrice, lowerBandThreshold, bbTouchTolerance * 100);
            return false;
        }

        // 3. RSI check - must be oversold
        double rsi = indicators.getOrDefault("RSI", 50.0);
        if (rsi >= rsiOversold) {
            logger.debug("⏸️ {} Mean Reversion LONG filtered - RSI {:.1f} >= oversold {}",
                    symbol, rsi, rsiOversold);
            return false;
        }

        // 4. Optional: RSI divergence check (TODO: implement)
        if (divergenceEnabled) {
            // Will implement RSIDivergenceDetector later
            logger.debug("RSI divergence check not yet implemented");
        }

        // All checks passed
        logger.info("✅ {} Mean Reversion LONG confirmed: Price={:.2f}, BB[{:.2f}-{:.2f}], RSI={:.1f}, ADX={:.1f}",
                symbol, currentPrice, bbLower, bbUpper, rsi, adx);

        return true;
    }

    /**
     * Check if mean reversion SHORT entry is valid
     * 
     * @param symbol       Symbol name
     * @param series       Bar series
     * @param indicators   Pre-calculated indicators
     * @param currentPrice Current price
     * @return true if SHORT signal confirmed
     */
    public boolean checkShortEntry(String symbol, BarSeries series,
            Map<String, Double> indicators, double currentPrice) {

        if (!enabled) {
            return false;
        }

        // 1. ADX check - must be ranging
        double adx = indicators.getOrDefault("ADX", 100.0);
        if (adx >= adxMax) {
            logger.debug("⏸️ {} Mean Reversion SHORT filtered - ADX too high ({} >= {})",
                    symbol, adx, adxMax);
            return false;
        }

        // 2. Bollinger Bands check - price near/above upper band
        double bbLower = indicators.getOrDefault("BB_LOWER", 0.0);
        double bbMiddle = indicators.getOrDefault("BB_MIDDLE", currentPrice);
        double bbUpper = indicators.getOrDefault("BB_UPPER", currentPrice * 2);

        if (bbUpper == 0.0) {
            logger.debug("⏸️ {} Mean Reversion SHORT filtered - BB not available", symbol);
            return false;
        }

        // Price must be within tolerance of upper band
        double upperBandThreshold = bbUpper * (1 - bbTouchTolerance);
        if (currentPrice < upperBandThreshold) {
            logger.debug("⏸️ {} Mean Reversion SHORT filtered - Price {:.2f} < Upper BB {:.2f} (-{}%)",
                    symbol, currentPrice, upperBandThreshold, bbTouchTolerance * 100);
            return false;
        }

        // 3. RSI check - must be overbought
        double rsi = indicators.getOrDefault("RSI", 50.0);
        if (rsi <= rsiOverbought) {
            logger.debug("⏸️ {} Mean Reversion SHORT filtered - RSI {:.1f} <= overbought {}",
                    symbol, rsi, rsiOverbought);
            return false;
        }

        // 4. Optional: RSI divergence check (TODO: implement)
        if (divergenceEnabled) {
            // Will implement RSIDivergenceDetector later
            logger.debug("RSI divergence check not yet implemented");
        }

        // All checks passed
        logger.info("✅ {} Mean Reversion SHORT confirmed: Price={:.2f}, BB[{:.2f}-{:.2f}], RSI={:.1f}, ADX={:.1f}",
                symbol, currentPrice, bbLower, bbUpper, rsi, adx);

        return true;
    }

    /**
     * Calculate take profit target (middle Bollinger Band)
     * 
     * @param indicators Pre-calculated indicators
     * @return Target price
     */
    public double calculateTakeProfitTarget(Map<String, Double> indicators, double currentPrice) {
        double bbMiddle = indicators.getOrDefault("BB_MIDDLE", currentPrice);
        return bbMiddle;
    }

    /**
     * Calculate stop loss (beyond outer band)
     * 
     * @param indicators   Pre-calculated indicators
     * @param currentPrice Current price
     * @param isLong       true for LONG, false for SHORT
     * @return Stop loss price
     */
    public double calculateStopLoss(Map<String, Double> indicators,
            double currentPrice, boolean isLong) {

        double atr = indicators.getOrDefault("ATR", currentPrice * 0.02);
        double stopDistance = atr * 1.5; // 1.5x ATR

        if (isLong) {
            // LONG: Stop below entry
            double bbLower = indicators.getOrDefault("BB_LOWER", currentPrice * 0.98);
            return Math.min(currentPrice - stopDistance, bbLower * 0.99);
        } else {
            // SHORT: Stop above entry
            double bbUpper = indicators.getOrDefault("BB_UPPER", currentPrice * 1.02);
            return Math.max(currentPrice + stopDistance, bbUpper * 1.01);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
