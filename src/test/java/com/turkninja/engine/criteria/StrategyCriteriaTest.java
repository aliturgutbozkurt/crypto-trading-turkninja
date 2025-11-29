package com.turkninja.engine.criteria;

import com.turkninja.engine.IndicatorService;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.BaseBar;
import org.ta4j.core.Bar;
import org.ta4j.core.num.DecimalNum;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StrategyCriteriaTest {

    @Test
    public void testEMASlopeFilter() {
        // Use real service instead of mock to avoid Java 21 mocking issues
        IndicatorService indicatorService = new IndicatorService();
        EMASlopeFilter filter = new EMASlopeFilter(indicatorService);

        BarSeries series = new BaseBarSeriesBuilder().withName("test").build();
        Map<String, Double> indicators = new HashMap<>();

        // Populate series with data to create an upward slope
        // Need at least period (50) + lookback (10) bars = 60 bars
        ZonedDateTime time = ZonedDateTime.now();
        Duration duration = Duration.ofMinutes(5);

        for (int i = 0; i < 100; i++) {
            // Price increases by 1% every bar
            double price = 10000 * Math.pow(1.001, i);
            Instant endTime = time.plusMinutes(i * 5).toInstant();
            Instant beginTime = endTime.minus(duration);

            Bar bar = new BaseBar(duration,
                    endTime,
                    beginTime,
                    DecimalNum.valueOf(price),
                    DecimalNum.valueOf(price),
                    DecimalNum.valueOf(price),
                    DecimalNum.valueOf(price),
                    DecimalNum.valueOf(1000),
                    DecimalNum.valueOf(0),
                    0L);
            series.addBar(bar);
        }

        // Calculate indicators (optional, but good for consistency)
        // indicators = indicatorService.calculateIndicators(series);

        boolean resultLong = filter.evaluate("BTCUSDT", series, indicators,
                series.getLastBar().getClosePrice().doubleValue(), true);
        assertTrue(resultLong, "Should pass for LONG with positive slope");

        boolean resultShort = filter.evaluate("BTCUSDT", series, indicators,
                series.getLastBar().getClosePrice().doubleValue(), false);
        assertFalse(resultShort, "Should fail for SHORT with positive slope");
    }

    @Test
    public void testVolumeConfirmationFilter() {
        IndicatorService indicatorService = new IndicatorService();
        VolumeConfirmationFilter filter = new VolumeConfirmationFilter(indicatorService);

        BarSeries series = new BaseBarSeriesBuilder().withName("test").build();
        Map<String, Double> indicators = new HashMap<>();

        // Populate series with data
        // Need at least period (20) bars
        ZonedDateTime time = ZonedDateTime.now();
        Duration duration = Duration.ofMinutes(5);

        // 1. Test Sufficient Volume
        // 20 bars of volume 1000
        for (int i = 0; i < 20; i++) {
            Instant endTime = time.plusMinutes(i * 5).toInstant();
            Instant beginTime = endTime.minus(duration);

            Bar bar = new BaseBar(duration,
                    endTime,
                    beginTime,
                    DecimalNum.valueOf(100),
                    DecimalNum.valueOf(100),
                    DecimalNum.valueOf(100),
                    DecimalNum.valueOf(100),
                    DecimalNum.valueOf(1000),
                    DecimalNum.valueOf(0),
                    0L);
            series.addBar(bar);
        }
        // Last bar with volume 2000 (2x average)
        Instant endTimeLast = time.plusMinutes(100).toInstant();
        Bar barLast = new BaseBar(duration,
                endTimeLast,
                endTimeLast.minus(duration),
                DecimalNum.valueOf(100),
                DecimalNum.valueOf(100),
                DecimalNum.valueOf(100),
                DecimalNum.valueOf(100),
                DecimalNum.valueOf(2000),
                DecimalNum.valueOf(0),
                0L);
        series.addBar(barLast);

        boolean result = filter.evaluate("BTCUSDT", series, indicators, 100, true);
        assertTrue(result, "Should pass when volume is sufficient (2x avg)");

        // 2. Test Insufficient Volume
        BarSeries seriesLowVol = new BaseBarSeriesBuilder().withName("test_low").build();
        for (int i = 0; i < 20; i++) {
            Instant endTime = time.plusMinutes(i * 5).toInstant();
            Instant beginTime = endTime.minus(duration);

            Bar bar = new BaseBar(duration,
                    endTime,
                    beginTime,
                    DecimalNum.valueOf(100),
                    DecimalNum.valueOf(100),
                    DecimalNum.valueOf(100),
                    DecimalNum.valueOf(100),
                    DecimalNum.valueOf(1000),
                    DecimalNum.valueOf(0),
                    0L);
            seriesLowVol.addBar(bar);
        }
        // Last bar with volume 500 (0.5x average)
        Instant endTimeLow = time.plusMinutes(100).toInstant();
        Bar barLow = new BaseBar(duration,
                endTimeLow,
                endTimeLow.minus(duration),
                DecimalNum.valueOf(100),
                DecimalNum.valueOf(100),
                DecimalNum.valueOf(100),
                DecimalNum.valueOf(100),
                DecimalNum.valueOf(500),
                DecimalNum.valueOf(0),
                0L);
        seriesLowVol.addBar(barLow);

        result = filter.evaluate("BTCUSDT", seriesLowVol, indicators, 100, true);
        assertFalse(result, "Should fail when volume is insufficient (0.5x avg)");
    }
}
