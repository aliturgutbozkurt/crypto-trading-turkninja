package com.turkninja.engine.criteria;

import com.turkninja.config.Config;
import com.turkninja.engine.IndicatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.util.Map;

/**
 * Volume Confirmation Filter
 * 
 * Ensures sufficient volume backs the price move.
 * Low volume moves are unreliable and prone to fakeouts.
 * 
 * Requirement: Current volume >= 1.2x average volume (20-period)
 */
public class VolumeConfirmationFilter implements StrategyCriteria {

    private static final Logger logger = LoggerFactory.getLogger(VolumeConfirmationFilter.class);

    private final boolean enabled;
    private final double minMultiplier;
    private final int period;
    private final IndicatorService indicatorService;
    // Removed BarSeries field

    public VolumeConfirmationFilter(IndicatorService indicatorService) {
        this.enabled = Boolean.parseBoolean(Config.get("strategy.volume.filter.enabled", "true"));
        this.minMultiplier = Double.parseDouble(Config.get("strategy.volume.min.multiplier", "1.2"));
        this.period = Integer.parseInt(Config.get("strategy.volume.period", "20"));
        this.indicatorService = indicatorService;

        if (enabled) {
            logger.debug("✅ Volume Confirmation Filter initialized: minMultiplier={}, period={}",
                    minMultiplier, period);
        }
    }

    @Override
    public boolean evaluate(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        if (!enabled) {
            return true; // Filter disabled
        }

        // Check volume confirmation
        boolean passes = indicatorService.checkVolumeConfirmation(series, minMultiplier, period);

        if (!passes) {
            logger.debug("⏸️ {} {} filtered - Volume too low (< {:.1f}x average)",
                    symbol, isLong ? "LONG" : "SHORT", minMultiplier);
        }

        return passes;
    }

    @Override
    public String getFilterName() {
        return "Volume Confirmation";
    }

    @Override
    public String getFailureReason(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        return String.format("Volume < %.1fx average (insufficient volume backing)", minMultiplier);
    }
}
