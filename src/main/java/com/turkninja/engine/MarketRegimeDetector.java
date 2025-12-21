package com.turkninja.engine;

import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.Map;

/**
 * Market Regime Detector
 * 
 * Analyzes market conditions to classify regime type and recommend strategy.
 * Uses ADX (trend strength), volatility (ATR), and EMA slope to determine
 * whether market is trending, ranging, or choppy.
 */
public class MarketRegimeDetector {

    private static final Logger logger = LoggerFactory.getLogger(MarketRegimeDetector.class);

    private final IndicatorService indicatorService;

    // Configurable thresholds
    private final double adxTrendMin; // ADX > this = trending
    private final double adxRangeMax; // ADX < this = ranging
    private final double volatilityHigh; // ATR/Price > this = high vol
    private final double volatilityLow; // ATR/Price < this = low vol
    private final double emaSlopeStrong; // EMA slope > this = strong trend

    public MarketRegimeDetector(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;

        // Load configuration
        this.adxTrendMin = Config.getDouble("regime.adx.trend.min", 20.0);
        this.adxRangeMax = Config.getDouble("regime.adx.range.max", 15.0);
        this.volatilityHigh = Config.getDouble("regime.volatility.high", 2.5);
        this.volatilityLow = Config.getDouble("regime.volatility.low", 1.0);
        this.emaSlopeStrong = Config.getDouble("regime.ema.slope.strong", 0.01);

        logger.info("✅ Market Regime Detector initialized: ADX[{}-{}], Vol[{}-{}], EMA slope={}",
                adxRangeMax, adxTrendMin, volatilityLow, volatilityHigh, emaSlopeStrong);
    }

    /**
     * Detect current market regime for the given symbol
     * 
     * @param symbol     Symbol to analyze
     * @param series     Bar series with price data
     * @param indicators Pre-calculated indicators (optional, can pass null)
     * @return Detected market regime
     */
    public MarketRegime detectRegime(String symbol, BarSeries series, Map<String, Double> indicators) {
        try {
            // Calculate indicators if not provided
            if (indicators == null) {
                indicators = indicatorService.calculateIndicators(series);
            }

            double currentPrice = series.getLastBar().getClosePrice().doubleValue();

            // 1. Get ADX (trend strength)
            double adx = indicators.getOrDefault("ADX", 0.0);

            // 2. Get EMA50 and calculate slope
            double ema50 = indicators.getOrDefault("EMA_50", currentPrice);
            double emaSlope = calculateEMASlope(series, 50, 10);

            // 3. Get volatility (ATR as % of price)
            double atr = indicators.getOrDefault("ATR", 0.0);
            double volatilityPercent = (atr / currentPrice) * 100.0;

            // 4. Calculate Hurst Exponent (H > 0.5 = trending, H < 0.5 = mean reverting)
            double hurstExponent = indicatorService.calculateHurstExponent(series, 100);
            boolean hurstTrending = hurstExponent > 0.55; // Clear trending signal
            boolean hurstMeanReverting = hurstExponent < 0.45; // Clear mean reversion signal

            // 5. Determine trend direction
            boolean priceAboveEMA = currentPrice > ema50;
            boolean emaSlopePositive = emaSlope > 0;
            boolean emaSlopeNegative = emaSlope < 0;
            boolean strongSlope = Math.abs(emaSlope) > emaSlopeStrong;

            // 6. Classify regime (enhanced with Hurst)
            MarketRegime regime = classifyRegimeWithHurst(
                    adx, volatilityPercent, priceAboveEMA,
                    emaSlopePositive, emaSlopeNegative, strongSlope,
                    hurstExponent, hurstTrending, hurstMeanReverting);

            logger.debug("Regime {}: ADX={:.1f}, Vol={:.2f}%, Hurst={:.3f}, EMASlope={:.3f}%, Price {}EMA",
                    regime, adx, volatilityPercent, hurstExponent, emaSlope * 100,
                    priceAboveEMA ? ">" : "<");

            return regime;

        } catch (Exception e) {
            logger.error("Error detecting regime for {}", symbol, e);
            return MarketRegime.CHOPPY; // Safe fallback
        }
    }

    /**
     * Overload: Detect regime without pre-calculated indicators
     */
    public MarketRegime detectRegime(String symbol, BarSeries series) {
        return detectRegime(symbol, series, null);
    }

    /**
     * Classify market regime based on indicators
     */
    private MarketRegime classifyRegime(double adx, double volatilityPercent,
            boolean priceAboveEMA, boolean emaSlopePositive,
            boolean emaSlopeNegative, boolean strongSlope) {

        // Strong Trending Markets (ADX > threshold)
        if (adx >= adxTrendMin) {
            if (priceAboveEMA && emaSlopePositive) {
                return strongSlope ? MarketRegime.STRONG_UPTREND : MarketRegime.WEAK_UPTREND;
            } else if (!priceAboveEMA && emaSlopeNegative) {
                return strongSlope ? MarketRegime.STRONG_DOWNTREND : MarketRegime.WEAK_DOWNTREND;
            } else {
                // ADX high but conflicting signals
                return MarketRegime.CHOPPY;
            }
        }

        // Weak Trending Markets (ADX between range and trend thresholds)
        else if (adx > adxRangeMax && adx < adxTrendMin) {
            if (priceAboveEMA && emaSlopePositive) {
                return MarketRegime.WEAK_UPTREND;
            } else if (!priceAboveEMA && emaSlopeNegative) {
                return MarketRegime.WEAK_DOWNTREND;
            } else {
                // Weak trend with conflicting signals
                return MarketRegime.CHOPPY;
            }
        }

        // Ranging Markets (ADX < range threshold)
        else {
            if (volatilityPercent > volatilityHigh) {
                return MarketRegime.RANGING_HIGH_VOL;
            } else if (volatilityPercent < volatilityLow) {
                return MarketRegime.RANGING_LOW_VOL;
            } else {
                // Medium volatility ranging
                return MarketRegime.RANGING_HIGH_VOL; // Default to high vol for mean reversion
            }
        }
    }

    /**
     * Classify market regime with Hurst Exponent confirmation
     * Hurst provides mathematical verification of trending vs mean reverting
     * behavior
     * 
     * @param hurstExponent      H value (0-1)
     * @param hurstTrending      true if H > 0.55 (persistent/trending)
     * @param hurstMeanReverting true if H < 0.45 (anti-persistent/mean reverting)
     */
    private MarketRegime classifyRegimeWithHurst(double adx, double volatilityPercent,
            boolean priceAboveEMA, boolean emaSlopePositive,
            boolean emaSlopeNegative, boolean strongSlope,
            double hurstExponent, boolean hurstTrending, boolean hurstMeanReverting) {

        // If Hurst strongly indicates mean reversion (H < 0.45), prefer ranging
        if (hurstMeanReverting && adx < adxTrendMin) {
            if (volatilityPercent > volatilityHigh) {
                return MarketRegime.RANGING_HIGH_VOL;
            } else {
                return MarketRegime.RANGING_LOW_VOL;
            }
        }

        // If Hurst strongly indicates trending (H > 0.55) AND ADX confirms
        if (hurstTrending && adx >= adxTrendMin * 0.8) { // Relaxed ADX threshold with Hurst confirmation
            if (priceAboveEMA && emaSlopePositive) {
                return strongSlope ? MarketRegime.STRONG_UPTREND : MarketRegime.WEAK_UPTREND;
            } else if (!priceAboveEMA && emaSlopeNegative) {
                return strongSlope ? MarketRegime.STRONG_DOWNTREND : MarketRegime.WEAK_DOWNTREND;
            }
        }

        // Strong Trending Markets (ADX > threshold)
        if (adx >= adxTrendMin) {
            if (priceAboveEMA && emaSlopePositive) {
                return strongSlope ? MarketRegime.STRONG_UPTREND : MarketRegime.WEAK_UPTREND;
            } else if (!priceAboveEMA && emaSlopeNegative) {
                return strongSlope ? MarketRegime.STRONG_DOWNTREND : MarketRegime.WEAK_DOWNTREND;
            } else {
                // ADX high but conflicting signals - check Hurst for guidance
                if (hurstMeanReverting) {
                    return MarketRegime.RANGING_HIGH_VOL;
                }
                return MarketRegime.CHOPPY;
            }
        }

        // Weak Trending Markets
        else if (adx > adxRangeMax && adx < adxTrendMin) {
            // Hurst can override weak ADX signals
            if (hurstTrending && priceAboveEMA && emaSlopePositive) {
                return MarketRegime.WEAK_UPTREND;
            } else if (hurstTrending && !priceAboveEMA && emaSlopeNegative) {
                return MarketRegime.WEAK_DOWNTREND;
            } else if (hurstMeanReverting) {
                return volatilityPercent > volatilityHigh ? MarketRegime.RANGING_HIGH_VOL
                        : MarketRegime.RANGING_LOW_VOL;
            } else {
                return MarketRegime.CHOPPY;
            }
        }

        // Ranging Markets (ADX < range threshold)
        else {
            if (volatilityPercent > volatilityHigh) {
                return MarketRegime.RANGING_HIGH_VOL;
            } else if (volatilityPercent < volatilityLow) {
                return MarketRegime.RANGING_LOW_VOL;
            } else {
                return MarketRegime.RANGING_HIGH_VOL;
            }
        }
    }

    /**
     * Calculate EMA slope over lookback period
     * Returns % change per bar
     */
    private double calculateEMASlope(BarSeries series, int emaPeriod, int lookback) {
        if (series.getBarCount() < emaPeriod + lookback) {
            return 0.0;
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator ema = new EMAIndicator(closePrice, emaPeriod);

        int endIndex = series.getEndIndex();
        int startIndex = endIndex - lookback;

        double currentEMA = ema.getValue(endIndex).doubleValue();
        double pastEMA = ema.getValue(startIndex).doubleValue();

        // Slope as % change per bar
        double slope = ((currentEMA - pastEMA) / pastEMA) / lookback;

        return slope;
    }

    /**
     * Get trend strength as confidence score (0-100)
     * Higher values = stronger/clearer trend
     */
    public double getTrendStrength(MarketRegime regime, double adx) {
        if (regime.isTrending()) {
            // Normalize ADX to 0-100 scale
            // ADX 25 = 50, ADX 50 = 100
            return Math.min(100, (adx / 50.0) * 100.0);
        } else if (regime.isRanging()) {
            // Ranging markets: inverse of ADX
            // Lower ADX = stronger ranging signal
            return Math.max(0, 100 - (adx / adxRangeMax) * 50.0);
        } else {
            return 0; // Choppy = no confidence
        }
    }

    /**
     * Check if regime is suitable for trend-following
     */
    public boolean allowTrendFollowing(MarketRegime regime) {
        return regime.isTrending();
    }

    /**
     * Check if regime is suitable for mean reversion
     */
    public boolean allowMeanReversion(MarketRegime regime) {
        return regime.isRanging();
    }

    /**
     * Get recommended position size multiplier based on regime
     * Returns 0.0 to 1.0 (percentage of normal size)
     */
    public double getPositionSizeMultiplier(MarketRegime regime) {
        switch (regime) {
            case STRONG_UPTREND:
            case STRONG_DOWNTREND:
                return 1.0; // Full size for strong trends

            case WEAK_UPTREND:
            case WEAK_DOWNTREND:
                return 0.75; // Reduced size for weak trends

            case RANGING_HIGH_VOL:
                return 0.5; // Half size for ranging (mean reversion)

            case RANGING_LOW_VOL:
                return 0.3; // Small size for low volatility

            case CHOPPY:
            default:
                return 0.0; // No new positions in choppy markets
        }
    }
}
