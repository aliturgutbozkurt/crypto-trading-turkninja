package com.turkninja.engine;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.Num;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IndicatorService {

    public Map<String, Double> calculateIndicators(BarSeries series) {
        Map<String, Double> results = new HashMap<>();
        if (series.getBarCount() == 0)
            return results;

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // RSI
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        double currentRSI = rsi.getValue(series.getEndIndex()).doubleValue();
        results.put("RSI", currentRSI);

        // RSI Bands (Bollinger Bands applied to RSI values)
        if (series.getBarCount() >= 20) {
            List<Double> rsiHistory = new ArrayList<>();
            int startIndex = Math.max(0, series.getEndIndex() - 19);

            for (int i = startIndex; i <= series.getEndIndex(); i++) {
                rsiHistory.add(rsi.getValue(i).doubleValue());
            }

            // Calculate RSI standard deviation
            double rsiMean = rsiHistory.stream().mapToDouble(Double::doubleValue).average().orElse(50.0);
            double rsiVariance = rsiHistory.stream()
                    .mapToDouble(val -> Math.pow(val - rsiMean, 2))
                    .average()
                    .orElse(0.0);
            double rsiStdDev = Math.sqrt(rsiVariance);

            // RSI Bands with 2x standard deviation multiplier
            double rsiBandsMultiplier = Double.parseDouble(
                    com.turkninja.config.Config.get("strategy.rsi.bands.multiplier", "2.0"));

            double rsiUpperBand = currentRSI + (rsiStdDev * rsiBandsMultiplier);
            double rsiLowerBand = currentRSI - (rsiStdDev * rsiBandsMultiplier);
            double rsiBandwidth = rsiUpperBand - rsiLowerBand;

            // Clamp bands to RSI range [0, 100]
            rsiUpperBand = Math.min(100, rsiUpperBand);
            rsiLowerBand = Math.max(0, rsiLowerBand);

            results.put("RSI_UPPER_BAND", rsiUpperBand);
            results.put("RSI_LOWER_BAND", rsiLowerBand);
            results.put("RSI_BANDWIDTH", rsiBandwidth);
        }

        // MACD
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);
        results.put("MACD", macd.getValue(series.getEndIndex()).doubleValue());
        results.put("MACD_SIGNAL", macdSignal.getValue(series.getEndIndex()).doubleValue());

        // Bollinger Bands
        EMAIndicator avg14 = new EMAIndicator(closePrice, 20);
        StandardDeviationIndicator sd14 = new StandardDeviationIndicator(closePrice, 20);
        BollingerBandsMiddleIndicator middleBB = new BollingerBandsMiddleIndicator(avg14);
        BollingerBandsLowerIndicator lowerBB = new BollingerBandsLowerIndicator(middleBB, sd14);
        BollingerBandsUpperIndicator upperBB = new BollingerBandsUpperIndicator(middleBB, sd14);

        results.put("BB_LOWER", lowerBB.getValue(series.getEndIndex()).doubleValue());
        results.put("BB_MIDDLE", middleBB.getValue(series.getEndIndex()).doubleValue());
        results.put("BB_UPPER", upperBB.getValue(series.getEndIndex()).doubleValue());

        // EMA 9 and 21 for scalping trend detection
        EMAIndicator ema9 = new EMAIndicator(closePrice, 9);
        EMAIndicator ema21 = new EMAIndicator(closePrice, 21);
        results.put("EMA_9", ema9.getValue(series.getEndIndex()).doubleValue());
        results.put("EMA_21", ema21.getValue(series.getEndIndex()).doubleValue());

        // EMA 50 for overall trend (keep for BTC analysis)
        EMAIndicator ema50 = new EMAIndicator(closePrice, 50);
        results.put("EMA_50", ema50.getValue(series.getEndIndex()).doubleValue());

        // Volume spike detection (current vs average)
        if (series.getBarCount() >= 20) {
            double currentVolume = series.getLastBar().getVolume().doubleValue();
            double avgVolume = 0.0;
            for (int i = series.getEndIndex() - 19; i <= series.getEndIndex(); i++) {
                avgVolume += series.getBar(i).getVolume().doubleValue();
            }
            avgVolume /= 20;
            results.put("VOLUME_CURRENT", currentVolume);
            results.put("VOLUME_AVG", avgVolume);
            results.put("VOLUME_RATIO", avgVolume > 0 ? currentVolume / avgVolume : 0.0);
        }

        // ATR for volatility filter
        if (series.getBarCount() >= 14) {
            double atr = 0.0;
            for (int i = series.getEndIndex() - 13; i <= series.getEndIndex(); i++) {
                double high = series.getBar(i).getHighPrice().doubleValue();
                double low = series.getBar(i).getLowPrice().doubleValue();
                double prevClose = i > 0 ? series.getBar(i - 1).getClosePrice().doubleValue() : low;
                double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
                atr += tr;
            }
            atr /= 14;
            double currentPrice = closePrice.getValue(series.getEndIndex()).doubleValue();
            results.put("ATR", atr);
            results.put("ATR_PERCENT", currentPrice > 0 ? (atr / currentPrice) * 100 : 0.0);
        }

        // Nadaraya-Watson Envelope
        if (series.getBarCount() >= 100) {
            java.util.List<Double> closePrices = new java.util.ArrayList<>();
            for (int i = 0; i <= series.getEndIndex(); i++) {
                closePrices.add(series.getBar(i).getClosePrice().doubleValue());
            }

            // Read NWE parameters from config
            int nweLookback = Integer.parseInt(com.turkninja.config.Config.get("strategy.nwe.lookback", "200"));
            double nweBandwidth = Double.parseDouble(com.turkninja.config.Config.get("strategy.nwe.bandwidth", "8"));
            double nweMultiplier = Double
                    .parseDouble(com.turkninja.config.Config.get("strategy.nwe.multiplier", "2.5"));

            Map<String, Double> nwe = NadarayaWatsonCalculator.calculateNWE(closePrices, nweLookback, nweBandwidth,
                    nweMultiplier);
            results.putAll(nwe);
        }

        return results;
    }

    public BarSeries createBarSeries(String name) {
        return new BaseBarSeriesBuilder().withName(name).build();
    }

    public void addBar(BarSeries series, ZonedDateTime time, double open, double high, double low, double close,
            double volume) {
        series.addBar(time, open, high, low, close, volume);
    }
}
