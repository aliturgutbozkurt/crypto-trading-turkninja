package com.turkninja.engine.criteria;

import com.turkninja.config.Config;
import com.turkninja.engine.IndicatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.averages.EMAIndicator;

import java.util.Map;

/**
 * EMA Slope Filter
 * 
 * Checks if the EMA (Exponential Moving Average) has sufficient upward/downward
 * momentum.
 * Flat EMA = No clear momentum = Avoid trade
 * 
 * LONG: Requires positive slope (≥ +0.05% default)
 * SHORT: Requires negative slope (≤ -0.05% default)
 */
public class EMASlopeFilter implements StrategyCriteria {

    private static final Logger logger = LoggerFactory.getLogger(EMASlopeFilter.class);

    private final boolean enabled;
    private final int period;
    private final int lookback;
    private final double minPercent;
    private final IndicatorService indicatorService;
    // Removed BarSeries field as it's now passed in evaluate

    public EMASlopeFilter(IndicatorService indicatorService) {
        this(indicatorService,
                Integer.parseInt(Config.get("strategy.ema.slope.period", "50")),
                Integer.parseInt(Config.get("strategy.ema.slope.lookback", "10")),
                Double.parseDouble(Config.get("strategy.ema.slope.min.percent", "0.05")));
    }

    public EMASlopeFilter(IndicatorService indicatorService, int period, int lookback, double minPercent) {
        this.indicatorService = indicatorService;
        this.enabled = Boolean.parseBoolean(Config.get("strategy.ema.slope.enabled", "true"));
        this.period = period;
        this.lookback = lookback;
        this.minPercent = minPercent;

        if (enabled) {
            logger.debug("✅ EMA Slope Filter initialized: period={}, lookback={}, minPercent={}%",
                    period, lookback, minPercent);
        }
    }

    @Override
    public boolean evaluate(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        if (!enabled) {
            return true; // Filter disabled
        }

        // Calculate EMA slope
        double slope = indicatorService.calculateEMASlope(series, period, lookback);

        boolean passes;
        if (isLong) {
            // LONG: Need upward momentum
            passes = slope >= minPercent;
            if (!passes) {
                logger.info("⏸️ {} LONG filtered - EMA slope too flat ({}% < {}%)",
                        symbol, String.format("%.3f", slope), String.format("%.3f", minPercent));
            }
        } else {
            // SHORT: Need downward momentum
            passes = slope <= -minPercent;
            if (!passes) {
                logger.info("⏸️ {} SHORT filtered - EMA slope too flat ({}% > -{}%)",
                        symbol, String.format("%.3f", slope), String.format("%.3f", minPercent));
            }
        }

        return passes;
    }

    @Override
    public String getFilterName() {
        return "EMA Slope (Trend Momentum)";
    }

    @Override
    public String getFailureReason(String symbol, org.ta4j.core.BarSeries series, Map<String, Double> indicators,
            double currentPrice, boolean isLong) {
        double slope = indicatorService.calculateEMASlope(series, period, lookback);
        if (isLong) {
            return String.format("EMA slope %.3f%% < %.3f%% (insufficient upward momentum)", slope, minPercent);
        } else {
            return String.format("EMA slope %.3f%% > -%.3f%% (insufficient downward momentum)", slope, minPercent);
        }
    }
}
