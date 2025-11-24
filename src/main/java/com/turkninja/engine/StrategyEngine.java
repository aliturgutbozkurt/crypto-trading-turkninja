package com.turkninja.engine;

import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.config.Config;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class StrategyEngine {
    private static final Logger logger = LoggerFactory.getLogger(StrategyEngine.class);
    private final FuturesBinanceService futuresService;
    private final FuturesWebSocketService webSocketService;
    private final IndicatorService indicatorService;
    private final RiskManager riskManager;
    private final PositionTracker positionTracker;

    private List<String> tradingSymbols = Arrays.asList(
            "ETHUSDT", "SOLUSDT", "DOGEUSDT", "XRPUSDT", "BCHUSDT", "ATOMUSDT",
            "ALGOUSDT", "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT");

    private ScheduledExecutorService tradingScheduler;
    private volatile boolean tradingActive = false;

    // Position sizing parameters
    // private static final double MAX_POSITION_PERCENT = 0.25; // Moved to Config
    // private static final double MIN_POSITION_USDT = 4.0; // Moved to Config

    public StrategyEngine(FuturesBinanceService futuresService, FuturesWebSocketService webSocketService,
            IndicatorService indicatorService, RiskManager riskManager, PositionTracker positionTracker) {
        this.futuresService = futuresService;
        this.webSocketService = webSocketService;
        this.indicatorService = indicatorService;
        this.riskManager = riskManager;
        this.positionTracker = positionTracker;
    }

    private volatile String btcTrend = "NEUTRAL"; // BULLISH, BEARISH, NEUTRAL

    public void startAutomatedTrading() {
        if (tradingActive) {
            logger.warn("Automated trading is already active");
            return;
        }

        tradingActive = true;
        // Use a single thread for scheduling, but spawn Virtual Threads for execution
        tradingScheduler = Executors.newSingleThreadScheduledExecutor();

        // Schedule analysis for all symbols every 1 minute (using latest 15m candles)
        tradingScheduler.scheduleAtFixedRate(() -> {
            try {
                if (tradingActive) {
                    logger.info("Strategy loop started");
                    // Analyze BTC first to determine market trend
                    analyzeBTC();

                    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (String symbol : tradingSymbols) {
                            if (!symbol.equals("BTCUSDT")) { // BTC already analyzed
                                executor.submit(() -> {
                                    try {
                                        analyzeAndTrade(symbol);
                                    } catch (Exception e) {
                                        logger.error("Error in automated trading for " + symbol, e);
                                    }
                                });
                            }
                        }
                    } // Executor closes here, waiting for all virtual threads to complete
                }
            } catch (Throwable t) {
                logger.error("CRITICAL: Strategy scheduler failed", t);
            }
        }, 0, 1, TimeUnit.MINUTES);

        logger.info("Automated trading started for symbols: {}", tradingSymbols);
    }

    public void stopAutomatedTrading() {
        tradingActive = false;
        if (tradingScheduler != null) {
            tradingScheduler.shutdown();
            logger.info("Automated trading stopped");
        }
    }

    public boolean isTradingActive() {
        return tradingActive;
    }

    private void analyzeBTC() {
        try {
            String symbol = "BTCUSDT";
            // Get klines from WebSocket cache (NO REST API CALL)
            List<JSONObject> klines = webSocketService.getCachedKlines(symbol, 100);
            if (klines.isEmpty()) {
                logger.warn("No cached klines for {}, skipping analysis", symbol);
                return;
            }
            BarSeries series = convertCachedKlinesToBarSeries(symbol, klines);
            Map<String, Double> indicators = indicatorService.calculateIndicators(series);

            double currentPrice = series.getLastBar().getClosePrice().doubleValue();
            double ema50 = indicators.getOrDefault("EMA_50", currentPrice); // Assuming EMA 50 is added to
                                                                            // IndicatorService
            double macd = indicators.getOrDefault("MACD", 0.0);
            double macdSignal = indicators.getOrDefault("MACD_SIGNAL", 0.0);

            // Simple Trend Logic
            if (currentPrice > ema50 && macd > macdSignal) {
                btcTrend = "BULLISH";
            } else if (currentPrice < ema50 && macd < macdSignal) {
                btcTrend = "BEARISH";
            } else {
                btcTrend = "NEUTRAL";
            }
            logger.info("BTC Trend Analysis: {} (Price=${})", btcTrend, currentPrice);

        } catch (Exception e) {
            logger.error("Error analyzing BTC trend", e);
            btcTrend = "NEUTRAL"; // Fallback
        }
    }

    private void analyzeAndTrade(String symbol) {
        try {
            // 1. Check if we already have a position
            if (hasActivePosition(symbol)) {
                logger.debug("Skipping {} - already has active position", symbol);
                return;
            }

            // 2. Log BTC trend but don't block on NEUTRAL (was causing zero trades)
            logger.info("BTC Trend: {} - Analyzing altcoin {}", btcTrend, symbol);

            // 2. Fetch Data from WebSocket cache (NO REST API CALL)
            List<JSONObject> klines = webSocketService.getCachedKlines(symbol, 100);
            if (klines.isEmpty()) {
                logger.warn("No cached klines for {}, skipping analysis", symbol);
                return;
            }
            BarSeries series = convertCachedKlinesToBarSeries(symbol, klines);

            // 3. Calculate Indicators
            Map<String, Double> indicators = indicatorService.calculateIndicators(series);

            // 4. Get Current Price and RSI
            double currentPrice = series.getLastBar().getClosePrice().doubleValue();
            double rsi = indicators.getOrDefault("RSI", 50.0);

            // Get scalping indicators
            double ema9 = indicators.getOrDefault("EMA_9", currentPrice);
            double ema21 = indicators.getOrDefault("EMA_21", currentPrice);
            double volumeRatio = indicators.getOrDefault("VOLUME_RATIO", 0.0);
            double atrPercent = indicators.getOrDefault("ATR_PERCENT", 0.0);

            logger.info("{} | Price={} | RSI={} | EMA21={} | Vol={} | BTC={}",
                    symbol, currentPrice, rsi, ema21, volumeRatio, btcTrend);

            // AGGRESSIVE STRATEGY - Enter trades frequently
            // Quality through TP/SL and circuit breaker, not entry filters

            // LONG: Just need uptrend + RSI not extreme
            boolean isBuySignal = false;
            String buyReason = "";

            if (currentPrice > ema21 && rsi > 30 && rsi < 70) {
                isBuySignal = true;
                buyReason = String.format("LONG: Price>EMA + RSI(%.0f)", rsi);
                logger.info("🟢 {} LONG", symbol);
            }

            if (isBuySignal) {
                String msg = String.format("🟢 BUY SIGNAL for %s: %s (Price=%.2f, EMA=%.2f)",
                        symbol, buyReason, currentPrice, ema21);
                logger.info(msg);
                System.out.println(msg);
                executeEntry(symbol, "BUY", currentPrice);
            }

            // SHORT: Just need downtrend + RSI not extreme
            boolean isSellSignal = false;
            String sellReason = "";

            if (currentPrice < ema21 && rsi > 30 && rsi < 70) {
                isSellSignal = true;
                sellReason = String.format("SHORT: Price<EMA + RSI(%.0f)", rsi);
                logger.info("🔴 {} SHORT", symbol);
            }

            if (isSellSignal) {
                String msg = String.format("🔴 SELL SIGNAL for %s: %s (Price=%.2f, EMA9=%.2f, EMA21=%.2f)",
                        symbol, sellReason, currentPrice, ema9, ema21);
                logger.info(msg);
                System.out.println(msg);
                executeEntry(symbol, "SELL", currentPrice);
            }

        } catch (Exception e) {
            logger.error("Error analyzing symbol: " + symbol, e);
        }
    }

    private void executeEntry(String symbol, String side, double price) {
        try {
            // 1. Calculate position size
            double positionSize = calculatePositionSize(symbol, price);
            double minPositionUsdt = Config.getDouble("strategy.position.min_usdt", 4.0);

            if (positionSize < minPositionUsdt) {
                logger.warn("Position size too small for {}: ${}", symbol, positionSize);
                return;
            }

            // 2. Check risk limits
            if (!riskManager.canOpenPosition(positionSize)) {
                logger.warn("Risk limits prevent opening position for {}", symbol);
                return;
            }

            // 3. Calculate quantity (with 20x leverage)
            double quantity = calculateQuantity(symbol, positionSize, price);

            logger.info("Opening {} position: {} @ ${} (Size: ${}, Qty: {})",
                    side, symbol, price, positionSize, quantity);

            // 4. Place order
            String orderResult = futuresService.placeOrder(
                    symbol,
                    side.equals("BUY") ? "BUY" : "SELL",
                    quantity);

            logger.info("Order placed for {}: {}", symbol, orderResult);

            // 5. Track position
            positionTracker.trackPosition(symbol, side, price, quantity);

            // 6. Alert: Sound + Red console output
            System.out.print("\007"); // System beep
            String redColor = "\u001B[31m";
            String resetColor = "\u001B[0m";
            String boldRed = "\u001B[1;31m";
            System.out.println(boldRed + "═══════════════════════════════════════════════════════════" + resetColor);
            System.out.println(boldRed + "🚨 POSITION OPENED: " + side + " " + symbol + " @ $" + price + resetColor);
            System.out.println(
                    boldRed + "   Size: $" + String.format("%.2f", positionSize) + " | Qty: " + quantity + resetColor);
            System.out.println(boldRed + "═══════════════════════════════════════════════════════════" + resetColor);

        } catch (Exception e) {
            logger.error("Error executing entry for " + symbol, e);
        }
    }

    private double calculatePositionSize(String symbol, double price) {
        try {
            // Get available balance
            String accountJson = futuresService.getAccountInfo();
            JSONObject account = new JSONObject(accountJson);
            double availableBalance = account.getDouble("availableBalance");

            double maxPercent = Config.getDouble("strategy.position.max_percent", 0.25);
            double positionSize = availableBalance * maxPercent;

            // Cap at risk manager's max position size
            positionSize = Math.min(positionSize, 100.0); // $100 max from RiskManager

            return positionSize;

        } catch (Exception e) {
            logger.error("Error calculating position size", e);
            return Config.getDouble("strategy.position.min_usdt", 4.0);
        }
    }

    private double calculateQuantity(String symbol, double positionSizeUsdt, double price) {
        // With 20x leverage, we can control 20x the position size
        double notionalValue = positionSizeUsdt * 20;
        double quantity = notionalValue / price;

        // Round to appropriate precision (varies by symbol)
        if (symbol.contains("BTC")) {
            quantity = Math.round(quantity * 1000.0) / 1000.0; // 3 decimals
        } else if (symbol.contains("ETH")) {
            quantity = Math.round(quantity * 100.0) / 100.0; // 2 decimals
        } else {
            quantity = Math.round(quantity * 10.0) / 10.0; // 1 decimal
        }

        return quantity;
    }

    private boolean hasActivePosition(String symbol) {
        return positionTracker.getPosition(symbol) != null;
    }

    public void analyze(String symbol) {
        try {
            // 1. Fetch Data from WebSocket cache (NO REST API CALL)
            List<JSONObject> klines = webSocketService.getCachedKlines(symbol, 100);
            if (klines.isEmpty()) {
                logger.warn("No cached klines for {}, skipping analysis", symbol);
                return;
            }
            BarSeries series = convertCachedKlinesToBarSeries(symbol, klines);

            // 2. Calculate Indicators
            Map<String, Double> indicators = indicatorService.calculateIndicators(series);

            // 3. Get Current Price
            double currentPrice = series.getLastBar().getClosePrice().doubleValue();

            // 4. Get indicator values
            double rsi = indicators.getOrDefault("RSI", 50.0);
            double macd = indicators.getOrDefault("MACD", 0.0);
            double lowerBB = indicators.getOrDefault("BB_LOWER", 0.0);

            logger.debug("{} Analysis: Price={}, RSI={}, MACD={}, BB_Low={}", symbol, currentPrice, rsi, macd, lowerBB);

            if (rsi < 30 && currentPrice < lowerBB) {
                logger.info("BUY SIGNAL for {} (Oversold + Below BB)", symbol);
            }

        } catch (Exception e) {
            logger.error("Error analyzing symbol: " + symbol, e);
        }
    }

    private BarSeries convertToBarSeries(String symbol, String klinesJson) {
        BarSeries series = indicatorService.createBarSeries(symbol);
        JSONArray klines = new JSONArray(klinesJson);

        for (int i = 0; i < klines.length(); i++) {
            JSONArray kline = klines.getJSONArray(i);
            long openTime = kline.getLong(0);
            double open = kline.getDouble(1);
            double high = kline.getDouble(2);
            double low = kline.getDouble(3);
            double close = kline.getDouble(4);
            double volume = kline.getDouble(5);

            ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(openTime), ZoneId.of("UTC"));
            indicatorService.addBar(series, time, open, high, low, close, volume);
        }
        return series;
    }

    /**
     * Convert cached WebSocket klines to BarSeries (avoids REST API call)
     */
    private BarSeries convertCachedKlinesToBarSeries(String symbol, List<JSONObject> klines) {
        BarSeries series = indicatorService.createBarSeries(symbol);

        for (JSONObject kline : klines) {
            long openTime = kline.getLong("openTime");
            double open = Double.parseDouble(kline.getString("open"));
            double high = Double.parseDouble(kline.getString("high"));
            double low = Double.parseDouble(kline.getString("low"));
            double close = Double.parseDouble(kline.getString("close"));
            double volume = Double.parseDouble(kline.getString("volume"));

            ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(openTime), ZoneId.of("UTC"));

            // Skip if this timestamp already exists in the series (prevents duplicate bar
            // error)
            if (series.getBarCount() > 0 && !series.isEmpty()) {
                ZonedDateTime lastBarTime = series.getLastBar().getEndTime();
                if (!time.isAfter(lastBarTime)) {
                    continue; // Skip duplicate or older bars
                }
            }

            indicatorService.addBar(series, time, open, high, low, close, volume);
        }
        return series;
    }

    public void shutdown() {
        stopAutomatedTrading();
    }
}
