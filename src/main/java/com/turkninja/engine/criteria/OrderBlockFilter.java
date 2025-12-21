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
 * Order Block Filter - Institutional Supply/Demand Zone Detection
 * 
 * Smart Money Concepts (SMC) based filter that identifies Order Blocks:
 * - Bullish OB: The last bearish candle before a significant move up
 * - Bearish OB: The last bullish candle before a significant move down
 * 
 * Order Blocks represent areas where institutional traders placed large orders.
 * Price often returns to these zones before continuing in the original
 * direction.
 * 
 * Entry is allowed when price is at or near an Order Block zone.
 */
public class OrderBlockFilter implements StrategyCriteria {

    private static final Logger logger = LoggerFactory.getLogger(OrderBlockFilter.class);

    private final boolean enabled;
    private final int obLookback;
    private final double obProximityPercent;
    private final double minMovePercent;
    private final IndicatorService indicatorService;

    /**
     * Represents an Order Block zone
     */
    public static class OrderBlock {
        public final double high;
        public final double low;
        public final boolean isBullish;
        public final int barIndex;
        public final double volume;

        public OrderBlock(double high, double low, boolean isBullish, int barIndex, double volume) {
            this.high = high;
            this.low = low;
            this.isBullish = isBullish;
            this.barIndex = barIndex;
            this.volume = volume;
        }

        public double getMidpoint() {
            return (high + low) / 2;
        }

        public boolean isInZone(double price) {
            return price >= low && price <= high;
        }
    }

    public OrderBlockFilter(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
        this.enabled = Config.getBoolean("strategy.orderblock.enabled", true);
        this.obLookback = Config.getInt("strategy.priceaction.ob.lookback", 20);
        this.obProximityPercent = Config.getDouble("strategy.orderblock.proximity", 0.003); // 0.3%
        this.minMovePercent = Config.getDouble("strategy.orderblock.min.move", 0.01); // 1% move to qualify

        if (enabled) {
            logger.info("✅ Order Block Filter initialized: lookback={}, proximity={}%, minMove={}%",
                    obLookback, obProximityPercent * 100, minMovePercent * 100);
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

        // Detect Order Blocks
        List<OrderBlock> orderBlocks = detectOrderBlocks(series);

        if (orderBlocks.isEmpty()) {
            // No Order Blocks found - allow trade based on other filters
            logger.debug("{} - No Order Blocks detected", symbol);
            return true;
        }

        // Check if price is near a relevant Order Block
        for (OrderBlock ob : orderBlocks) {
            if (isLong && ob.isBullish) {
                // For LONG: price should be at or near a bullish OB (demand zone)
                if (ob.isInZone(currentPrice)) {
                    logger.info("✅ {} LONG - Price IN Bullish Order Block [{:.2f}-{:.2f}]",
                            symbol, ob.low, ob.high);
                    return true;
                }
                double distance = Math.abs(currentPrice - ob.getMidpoint()) / currentPrice;
                if (distance <= obProximityPercent) {
                    logger.info("✅ {} LONG - Price NEAR Bullish Order Block [{:.2f}-{:.2f}]",
                            symbol, ob.low, ob.high);
                    return true;
                }
            } else if (!isLong && !ob.isBullish) {
                // For SHORT: price should be at or near a bearish OB (supply zone)
                if (ob.isInZone(currentPrice)) {
                    logger.info("✅ {} SHORT - Price IN Bearish Order Block [{:.2f}-{:.2f}]",
                            symbol, ob.low, ob.high);
                    return true;
                }
                double distance = Math.abs(currentPrice - ob.getMidpoint()) / currentPrice;
                if (distance <= obProximityPercent) {
                    logger.info("✅ {} SHORT - Price NEAR Bearish Order Block [{:.2f}-{:.2f}]",
                            symbol, ob.low, ob.high);
                    return true;
                }
            }
        }

        // Allow trade if no OB match but we have strong momentum
        Double adx = indicators.get("ADX");
        if (adx != null && adx > 30) {
            logger.debug("{} - No matching OB but strong trend (ADX={:.2f}), allowing", symbol, adx);
            return true;
        }

        return true; // Don't block aggressively - OB is supplementary filter
    }

    /**
     * Detect Order Blocks in the price series
     * 
     * Bullish OB: Last bearish candle before a significant move up
     * Bearish OB: Last bullish candle before a significant move down
     */
    private List<OrderBlock> detectOrderBlocks(BarSeries series) {
        List<OrderBlock> blocks = new ArrayList<>();
        int endIndex = series.getEndIndex();
        int startIndex = Math.max(0, endIndex - obLookback);

        for (int i = startIndex + 3; i <= endIndex; i++) {
            Bar candle1 = series.getBar(i - 3);
            Bar candle2 = series.getBar(i - 2);
            Bar candle3 = series.getBar(i - 1);
            Bar candle4 = series.getBar(i);

            double c1Open = candle1.getOpenPrice().doubleValue();
            double c1Close = candle1.getClosePrice().doubleValue();
            double c1High = candle1.getHighPrice().doubleValue();
            double c1Low = candle1.getLowPrice().doubleValue();
            double c1Volume = candle1.getVolume().doubleValue();

            double c4Close = candle4.getClosePrice().doubleValue();
            double c4Open = candle4.getOpenPrice().doubleValue();

            // Calculate move from candle1 to candle4
            double movePercent = Math.abs(c4Close - c1Close) / c1Close;

            if (movePercent < minMovePercent) {
                continue; // Not a significant move
            }

            // Bullish Order Block: Bearish candle followed by up move
            if (c1Close < c1Open && c4Close > c1High) {
                // Candle1 was bearish and price moved significantly higher
                blocks.add(new OrderBlock(c1High, c1Low, true, i - 3, c1Volume));
            }

            // Bearish Order Block: Bullish candle followed by down move
            if (c1Close > c1Open && c4Close < c1Low) {
                // Candle1 was bullish and price moved significantly lower
                blocks.add(new OrderBlock(c1High, c1Low, false, i - 3, c1Volume));
            }
        }

        // Keep only recent Order Blocks (max 3)
        if (blocks.size() > 3) {
            return blocks.subList(blocks.size() - 3, blocks.size());
        }

        return blocks;
    }

    @Override
    public String getFilterName() {
        return "Order Block (SMC)";
    }

    @Override
    public String getFailureReason(String symbol, BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        if (isLong) {
            return "Not near bullish Order Block (demand zone)";
        } else {
            return "Not near bearish Order Block (supply zone)";
        }
    }
}
