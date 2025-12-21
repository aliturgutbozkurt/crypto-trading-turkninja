package com.turkninja.engine.criteria;

import com.turkninja.config.Config;
import com.turkninja.engine.IndicatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Price Action Filter - Fair Value Gap (FVG) and Market Structure Detection
 * 
 * Smart Money Concepts (SMC) based filter that identifies:
 * 1. Fair Value Gaps (FVG) - price inefficiencies that act as "magnets"
 * 2. Market Structure Breaks (MSB) - Higher High/Lower Low breaks
 * 
 * FVG Detection:
 * - Bullish FVG: candle[i-2].high < candle[i].low (gap up)
 * - Bearish FVG: candle[i-2].low > candle[i].high (gap down)
 * 
 * Entry is allowed when price is near an FVG zone in the direction of trade.
 */
public class PriceActionFilter implements StrategyCriteria {

    private static final Logger logger = LoggerFactory.getLogger(PriceActionFilter.class);

    private final boolean enabled;
    private final int fvgLookback;
    private final double fvgProximityPercent;
    private final IndicatorService indicatorService;

    /**
     * Represents a Fair Value Gap zone
     */
    public static class FVGZone {
        public final double high;
        public final double low;
        public final boolean isBullish;
        public final int barIndex;

        public FVGZone(double high, double low, boolean isBullish, int barIndex) {
            this.high = high;
            this.low = low;
            this.isBullish = isBullish;
            this.barIndex = barIndex;
        }

        public double getMidpoint() {
            return (high + low) / 2;
        }

        public double getSize() {
            return high - low;
        }
    }

    public PriceActionFilter(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
        this.enabled = Config.getBoolean("strategy.priceaction.enabled", true);
        this.fvgLookback = Config.getInt("strategy.priceaction.fvg.lookback", 50);
        this.fvgProximityPercent = Config.getDouble("strategy.priceaction.fvg.proximity", 0.005); // 0.5%

        if (enabled) {
            logger.info("✅ Price Action Filter initialized: FVG lookback={}, proximity={}%",
                    fvgLookback, fvgProximityPercent * 100);
        }
    }

    @Override
    public boolean evaluate(String symbol, BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        if (!enabled) {
            return true;
        }

        if (series == null || series.getBarCount() < 10) {
            return true; // Not enough data
        }

        // Detect FVG zones
        List<FVGZone> fvgZones = detectFVGZones(series);

        if (fvgZones.isEmpty()) {
            // No FVG zones found - check market structure instead
            return checkMarketStructure(series, currentPrice, isLong);
        }

        // Check if price is near a relevant FVG zone
        for (FVGZone zone : fvgZones) {
            if (isLong && zone.isBullish) {
                // For LONG: price should be at or above a bullish FVG (demand zone)
                double distance = Math.abs(currentPrice - zone.getMidpoint()) / currentPrice;
                if (distance <= fvgProximityPercent) {
                    logger.info("✅ {} LONG - Price near Bullish FVG zone [{:.2f}-{:.2f}]",
                            symbol, zone.low, zone.high);
                    return true;
                }
            } else if (!isLong && !zone.isBullish) {
                // For SHORT: price should be at or below a bearish FVG (supply zone)
                double distance = Math.abs(currentPrice - zone.getMidpoint()) / currentPrice;
                if (distance <= fvgProximityPercent) {
                    logger.info("✅ {} SHORT - Price near Bearish FVG zone [{:.2f}-{:.2f}]",
                            symbol, zone.low, zone.high);
                    return true;
                }
            }
        }

        // No matching FVG zone - fall back to market structure check
        return checkMarketStructure(series, currentPrice, isLong);
    }

    /**
     * Detect Fair Value Gaps in the price series
     * 
     * Bullish FVG: When candle[i-2].high < candle[i].low
     * Bearish FVG: When candle[i-2].low > candle[i].high
     */
    private List<FVGZone> detectFVGZones(BarSeries series) {
        List<FVGZone> zones = new ArrayList<>();
        int endIndex = series.getEndIndex();
        int startIndex = Math.max(0, endIndex - fvgLookback);

        for (int i = startIndex + 2; i <= endIndex; i++) {
            Bar prevPrev = series.getBar(i - 2);
            Bar current = series.getBar(i);

            double prevPrevHigh = prevPrev.getHighPrice().doubleValue();
            double prevPrevLow = prevPrev.getLowPrice().doubleValue();
            double currentHigh = current.getHighPrice().doubleValue();
            double currentLow = current.getLowPrice().doubleValue();

            // Bullish FVG: Gap up between candle[i-2] high and candle[i] low
            if (prevPrevHigh < currentLow) {
                double gapSize = currentLow - prevPrevHigh;
                double gapPercent = gapSize / prevPrevHigh;

                // Only consider significant gaps (> 0.1%)
                if (gapPercent > 0.001) {
                    zones.add(new FVGZone(currentLow, prevPrevHigh, true, i));
                }
            }

            // Bearish FVG: Gap down between candle[i-2] low and candle[i] high
            if (prevPrevLow > currentHigh) {
                double gapSize = prevPrevLow - currentHigh;
                double gapPercent = gapSize / prevPrevLow;

                // Only consider significant gaps (> 0.1%)
                if (gapPercent > 0.001) {
                    zones.add(new FVGZone(prevPrevLow, currentHigh, false, i));
                }
            }
        }

        return zones;
    }

    /**
     * Check basic market structure (Higher Highs/Lower Lows)
     */
    private boolean checkMarketStructure(BarSeries series, double currentPrice, boolean isLong) {
        int endIndex = series.getEndIndex();
        if (endIndex < 5)
            return true;

        // Get recent swing points
        double recentHigh = Double.MIN_VALUE;
        double recentLow = Double.MAX_VALUE;
        double prevHigh = Double.MIN_VALUE;
        double prevLow = Double.MAX_VALUE;

        // Find highs and lows in last 10 and previous 10 candles
        for (int i = endIndex; i > endIndex - 5 && i >= 0; i--) {
            Bar bar = series.getBar(i);
            recentHigh = Math.max(recentHigh, bar.getHighPrice().doubleValue());
            recentLow = Math.min(recentLow, bar.getLowPrice().doubleValue());
        }

        for (int i = endIndex - 5; i > endIndex - 10 && i >= 0; i--) {
            Bar bar = series.getBar(i);
            prevHigh = Math.max(prevHigh, bar.getHighPrice().doubleValue());
            prevLow = Math.min(prevLow, bar.getLowPrice().doubleValue());
        }

        if (isLong) {
            // For LONG: Higher Low structure (bullish)
            boolean higherLow = recentLow > prevLow;
            return higherLow;
        } else {
            // For SHORT: Lower High structure (bearish)
            boolean lowerHigh = recentHigh < prevHigh;
            return lowerHigh;
        }
    }

    @Override
    public String getFilterName() {
        return "Price Action (FVG/MSB)";
    }

    @Override
    public String getFailureReason(String symbol, BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        if (isLong) {
            return "No bullish FVG zone nearby, no Higher Low structure";
        } else {
            return "No bearish FVG zone nearby, no Lower High structure";
        }
    }
}
