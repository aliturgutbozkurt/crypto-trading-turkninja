package com.turkninja.engine;

import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.infra.TelegramNotifier;
import com.turkninja.web.service.WebSocketPushService;
import com.turkninja.web.dto.SignalDTO;
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
import java.util.concurrent.ConcurrentHashMap;
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
    private final OrderBookService orderBookService;
    private final MultiTimeframeAnalyzer multiTfAnalyzer;
    private final TelegramNotifier telegram;
    private WebSocketPushService webSocketPushService; // Optional, for UI notifications

    private List<String> tradingSymbols = Arrays.asList(
            "ETHUSDT", "SOLUSDT", "DOGEUSDT", "XRPUSDT", "BCHUSDT", "ATOMUSDT",
            "ALGOUSDT", "DOTUSDT", "AVAXUSDT", "LINKUSDT", "BNBUSDT",
            "ADAUSDT", "NEARUSDT", "SANDUSDT", "MANAUSDT", "ARBUSDT");

    private ScheduledExecutorService tradingScheduler;
    private ScheduledExecutorService batchProcessor; // Processes batched signals
    private volatile boolean tradingActive = false;

    // Batch signal collection
    private final SignalBatch signalBatch = new SignalBatch();
    private boolean batchModeEnabled;
    private int batchTopN;
    private double minSignalScore;

    // Position sizing parameters
    // private static final double MAX_POSITION_PERCENT = 0.25; // Moved to Config
    // private static final double MIN_POSITION_USDT = 4.0; // Moved to Config

    public StrategyEngine(FuturesBinanceService futuresService, FuturesWebSocketService webSocketService,
            IndicatorService indicatorService, RiskManager riskManager, PositionTracker positionTracker,
            OrderBookService orderBookService, MultiTimeframeAnalyzer multiTfAnalyzer, TelegramNotifier telegram) {
        this.futuresService = futuresService;
        this.webSocketService = webSocketService;
        this.indicatorService = indicatorService;
        this.riskManager = riskManager;
        this.positionTracker = positionTracker;
        this.orderBookService = orderBookService;
        this.multiTfAnalyzer = multiTfAnalyzer;
        this.telegram = telegram;
        // Load configurable entry filter parameters
        this.rsiLongMin = Config.getDouble("strategy.rsi.long.min", 40.0);
        this.rsiLongMax = Config.getDouble("strategy.rsi.long.max", 80.0);
        this.rsiShortMin = Config.getDouble("strategy.rsi.short.min", 20.0);
        this.rsiShortMax = Config.getDouble("strategy.rsi.short.max", 60.0);
        this.macdSignalTolerance = Config.getDouble("strategy.macd.signal.tolerance", 0.0005);
        this.useMultiTimeframe = Boolean.parseBoolean(Config.get("strategy.use.multi.timeframe", "true"));

        // Batch signal selection config
        this.batchModeEnabled = Boolean.parseBoolean(Config.get("strategy.batch.enabled", "true"));
        this.batchTopN = Integer.parseInt(Config.get("strategy.batch.top.n", "3"));
        this.minSignalScore = Config.getDouble("strategy.signal.min.score", 50.0);
    }

    // Configurable entry filter parameters
    private double rsiLongMin;
    private double rsiLongMax;
    private double rsiShortMin;
    private double rsiShortMax;
    private double macdSignalTolerance;
    private boolean useMultiTimeframe;

    private volatile String btcTrend = "NEUTRAL"; // BULLISH, BEARISH, NEUTRAL

    // Anti-reversal mechanism: Track recently closed positions
    private final Map<String, Long> symbolCooldown = new ConcurrentHashMap<>();
    private final long COOLDOWN_MILLIS = 3 * 60 * 1000; // 3 minutes cooldown after closing position

    /**
     * Set WebSocket push service for UI signal notifications (optional)
     */
    public void setWebSocketPushService(WebSocketPushService webSocketPushService) {
        this.webSocketPushService = webSocketPushService;
        logger.info("WebSocket push service connected for signal notifications");
    }

    public void startAutomatedTrading() {
        if (tradingActive) {
            logger.warn("Automated trading is already active");
            return;
        }

        tradingActive = true;

        // Register listener for closed candles (new candle open)
        webSocketService.setKlineUpdateListener(kline -> {
            if (!tradingActive)
                return;

            try {
                String symbol = kline.getString("s");
                String interval = kline.getString("i");

                // We only care about 5m candles (as configured in WebSocketService)
                if (!"5m".equals(interval))
                    return;

                // 1. Analyze BTC Trend first if it's BTC
                if ("BTCUSDT".equals(symbol)) {
                    analyzeBTC();
                }

                // 2. Analyze Trading Symbols
                if (tradingSymbols.contains(symbol) && !symbol.equals("BTCUSDT")) {
                    // Use Virtual Thread for analysis to avoid blocking WebSocket thread
                    Thread.ofVirtual().start(() -> {
                        try {
                            analyzeAndTrade(symbol);
                        } catch (Exception e) {
                            logger.error("Error in automated trading for " + symbol, e);
                        }
                    });
                }
            } catch (Exception e) {
                logger.error("Error processing kline update", e);
            }
        });

        logger.info("Automated trading started (Event-Driven: 5m Candle Close)");
        logger.info("Monitoring symbols: {}", tradingSymbols);

        // Start batch processor if enabled
        if (batchModeEnabled) {
            batchProcessor = Executors.newSingleThreadScheduledExecutor();
            int batchWindowSeconds = Integer.parseInt(Config.get("strategy.batch.window.seconds", "60"));
            batchProcessor.scheduleAtFixedRate(() -> {
                try {
                    processBatchedSignals();
                } catch (Exception e) {
                    logger.error("Error processing batch signals", e);
                }
            }, batchWindowSeconds, batchWindowSeconds, TimeUnit.SECONDS);

            logger.info("✅ Batch signal processor started (window: {}s, top: {}, min score: {})",
                    batchWindowSeconds, batchTopN, minSignalScore);
        } else {
            logger.info("⚠️ Batch mode DISABLED - using immediate execution");
        }
    }

    public void stopAutomatedTrading() {
        tradingActive = false;
        if (tradingScheduler != null) {
            tradingScheduler.shutdown();
            logger.info("Automated trading stopped");
        }
        if (batchProcessor != null) {
            batchProcessor.shutdown();
            logger.info("Batch processor stopped");
        }
    }

    public boolean isTradingActive() {
        return tradingActive;
    }

    public List<String> getTradingSymbols() {
        return tradingSymbols;
    }

    private void analyzeBTC() {
        try {
            String symbol = "BTCUSDT";
            // Get klines from WebSocket cache (NO REST API CALL)
            List<JSONObject> klines = webSocketService.getCachedKlines(symbol, "5m", 100);
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

    public void analyzeAndTrade(String symbol) {
        try {
            // 1. Check if we already have a position
            if (hasActivePosition(symbol)) {
                logger.debug("Skipping {} - already has active position", symbol);
                return;
            }

            // 2. Check cooldown - prevent immediate reversal after closing
            Long lastCloseTime = symbolCooldown.get(symbol);
            if (lastCloseTime != null) {
                long timeSinceClose = System.currentTimeMillis() - lastCloseTime;
                if (timeSinceClose < COOLDOWN_MILLIS) {
                    long remainingSeconds = (COOLDOWN_MILLIS - timeSinceClose) / 1000;
                    logger.debug("Skipping {} - cooldown active ({}s remaining)", symbol, remainingSeconds);
                    return;
                }
                // Cooldown expired, remove it
                symbolCooldown.remove(symbol);
            }

            // 3. Fetch Data (5m only)
            List<JSONObject> klines5m = webSocketService.getCachedKlines(symbol, "5m", 100);

            if (klines5m.isEmpty()) {
                logger.warn("Insufficient cached klines for {} (5m: {}), skipping",
                        symbol, klines5m.size());
                return;
            }

            BarSeries series5m = convertCachedKlinesToBarSeries(symbol, klines5m);

            // 4. Calculate Indicators
            Map<String, Double> indicators5m = indicatorService.calculateIndicators(series5m);

            // 5. Get Values
            double currentPrice = series5m.getLastBar().getClosePrice().doubleValue();

            // 5m Trend Indicators
            double ema50_5m = indicators5m.getOrDefault("EMA_50", currentPrice);
            double rsi_5m = indicators5m.getOrDefault("RSI", 50.0);
            double macd = indicators5m.getOrDefault("MACD", 0.0);
            double macdSignal = indicators5m.getOrDefault("MACD_SIGNAL", 0.0);

            logger.info("{} | Price={} | 5m[RSI={:.1f}, EMA50={:.2f}, MACD={:.4f}/{:.4f}]",
                    symbol, currentPrice, rsi_5m, ema50_5m, macd, macdSignal);

            // --- 5-MINUTE STRATEGY (Trend + Momentum) ---

            // LONG Logic:
            // 1. Trend: Price > EMA 50
            // 2. Momentum: RSI > 50 (Bullish) AND RSI < 70 (Not Overbought)
            // 3. Confirmation: MACD > Signal (Bullish Alignment)

            boolean isBuySignal = false;
            String buyReason = "";

            // EMA buffer: +0.3% to avoid sideways false signals
            boolean trendUp = currentPrice > ema50_5m * 1.003;
            boolean momentumUp = rsi_5m > rsiLongMin && rsi_5m < rsiLongMax;
            boolean macdBullish = macd > (macdSignal + macdSignalTolerance);

            if (trendUp && momentumUp && macdBullish) {
                isBuySignal = true;
                buyReason = String.format(
                        "LONG: Trend UP (Price>EMA50) + Momentum (RSI %.0f) + MACD Bullish",
                        rsi_5m);
                logger.info("🟢 {} LONG Signal", symbol);
            }

            if (isBuySignal) {
                // BTC Trend Check: Don't LONG if BTC is bearish
                if (btcTrend.equals("BEARISH")) {
                    logger.info("⏸️ {} LONG filtered - BTC trend bearish", symbol);
                    return;
                }

                // Multi-Timeframe Confirmation: Check 1H trend
                if (useMultiTimeframe && !multiTfAnalyzer.isSignalConfirmed(symbol, "BUY")) {
                    logger.info("⏸️ {} LONG signal filtered - 1H trend not bullish", symbol);
                    return; // Skip this trade
                }

                // Batch mode: Calculate score and add to batch
                if (batchModeEnabled) {
                    SignalScore score = calculateSignalScore(symbol, "BUY", currentPrice,
                            rsi_5m, macd, macdSignal, ema50_5m, 0);
                    signalBatch.addSignal(score);
                    return; // Don't execute immediately
                }

                // Fallback: Immediate execution if batch mode disabled
                String msg = String.format("🟢 BUY SIGNAL for %s: %s (Price=%.2f)", symbol, buyReason, currentPrice);
                logger.info(msg);
                System.out.println(msg);
                // Push signal to UI before executing
                pushSignal(symbol, "BUY", buyReason, currentPrice, true, "PENDING");
                executeEntry(symbol, "BUY", currentPrice);
            }

            // SHORT Logic:
            // 1. Trend: Price < EMA 50
            // 2. Momentum: RSI < 50 (Bearish) AND RSI > 30 (Not Oversold)
            // 3. Confirmation: MACD < Signal (Bearish Alignment)

            boolean isSellSignal = false;
            String sellReason = "";

            // EMA buffer: -0.3% to avoid sideways false signals
            boolean trendDown = currentPrice < ema50_5m * 0.997;
            boolean momentumDown = rsi_5m < rsiShortMax && rsi_5m > rsiShortMin;
            boolean macdBearish = macd < (macdSignal - macdSignalTolerance);

            if (trendDown && momentumDown && macdBearish) {
                isSellSignal = true;
                sellReason = String.format(
                        "SHORT: Trend DOWN (Price<EMA50) + Momentum (RSI %.0f) + MACD Bearish",
                        rsi_5m);
                logger.info("🔴 {} SHORT Signal", symbol);
            }

            if (isSellSignal) {
                // BTC Trend Check: Don't SHORT if BTC is bullish
                if (btcTrend.equals("BULLISH")) {
                    logger.info("⏸️ {} SHORT filtered - BTC trend bullish", symbol);
                    return;
                }

                // Multi-Timeframe Confirmation: Check 1H trend
                if (useMultiTimeframe && !multiTfAnalyzer.isSignalConfirmed(symbol, "SELL")) {
                    logger.info("⏸️ {} SHORT signal filtered - 1H trend not bearish", symbol);
                    return; // Skip this trade
                }

                // Batch mode: Calculate score and add to batch
                if (batchModeEnabled) {
                    SignalScore score = calculateSignalScore(symbol, "SELL", currentPrice,
                            rsi_5m, macd, macdSignal, ema50_5m, 0);
                    signalBatch.addSignal(score);
                    return; // Don't execute immediately
                }

                // Fallback: Immediate execution if batch mode disabled
                String msg = String.format("🔴 SELL SIGNAL for %s: %s (Price=%.2f)", symbol, sellReason, currentPrice);
                logger.info(msg);
                System.out.println(msg);
                // Push signal to UI before executing
                pushSignal(symbol, "SELL", sellReason, currentPrice, true, "PENDING");
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
                pushSignal(symbol, side, "Blocked: Position Size Too Small (<$4)", price, false, "BLOCKED_SIZE");
                return;
            }

            // 2. Check risk limits
            if (!riskManager.canOpenPosition(positionSize)) {
                logger.warn("Risk limits prevent opening position for {}", symbol);
                pushSignal(symbol, side, "Blocked: Risk Limits (Max Pos/Daily Loss)", price, false, "BLOCKED_RISK");
                return;
            }

            // 3. Check Order Book slippage
            if (!orderBookService.isSlippageAcceptable(symbol, side, positionSize, price)) {
                logger.warn("⚠️ {} Order Book slippage too high - trade blocked", symbol);
                pushSignal(symbol, side, "Blocked: High Slippage (>0.5%)", price, false, "BLOCKED_SLIPPAGE");
                return;
            }

            // 4. Calculate quantity (with 20x leverage)
            double quantity = calculateQuantity(symbol, positionSize, price);

            // 5. Calculate Binance commission (0.04% for market orders, both entry and
            // exit)
            double notionalValue = quantity * price; // Actual position value
            double entryCommission = notionalValue * 0.0004; // 0.04% entry fee
            double exitCommission = notionalValue * 0.0004; // 0.04% exit fee (estimated)
            double totalCommission = entryCommission + exitCommission; // Total estimated fees

            logger.info("Opening {} position: {} @ ${} (Size: ${}, Qty: {}, Commission: ${})",
                    side, symbol, price, positionSize, quantity, String.format("%.4f", totalCommission));

            // 6. Place Order
            String orderId = futuresService.placeMarketOrder(symbol, side, quantity);
            logger.info("✅ Order placed for {}: {} {} @ {}", symbol, side, quantity, price);

            // 7. Track Position
            positionTracker.trackPosition(symbol, side, price, quantity);

            // 8. Send Telegram Notification
            if (telegram != null && telegram.isEnabled()) {
                telegram.notifyPositionOpened(symbol, side, price, quantity, positionSize);
            }

            // 9. Update Signal Status to EXECUTED
            pushSignal(symbol, side, "Trade Executed Successfully", price, true, "EXECUTED");

            // 10. Alert: Sound + Red console output
            System.out.print("\007"); // System beep
            String redColor = "\u001B[31m";
            String resetColor = "\u001B[0m";
            String boldRed = "\u001B[1;31m";
            System.out.println(boldRed + "═══════════════════════════════════════════════════════════" + resetColor);
            System.out.println(boldRed + "🚨 POSITION OPENED: " + side + " " + symbol + " @ $" + price + resetColor);
            System.out.println(
                    boldRed + "   Size: $" + String.format("%.2f", positionSize) + " | Qty: " + quantity + resetColor);
            System.out.println(
                    boldRed + "   Binance Fee: $" + String.format("%.4f", totalCommission) + " (Entry: $"
                            + String.format("%.4f", entryCommission) + " + Exit: $"
                            + String.format("%.4f", exitCommission) + ")" + resetColor);
            System.out.println(boldRed + "═══════════════════════════════════════════════════════════" + resetColor);

        } catch (Exception e) {
            logger.error("Failed to execute entry for " + symbol, e);
            pushSignal(symbol, side, "Execution Failed: " + e.getMessage(), price, false, "FAILED");
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
            List<JSONObject> klines = webSocketService.getCachedKlines(symbol, "5m", 100);
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

    /**
     * Push trading signal to UI via WebSocket (if connected)
     */
    private void pushSignal(String symbol, String type, String reason, double price, boolean executed, String status) {
        if (webSocketPushService != null) {
            try {
                SignalDTO signal = new SignalDTO(symbol, type, reason, price, executed, status);
                webSocketPushService.pushSignal(signal);
            } catch (Exception e) {
                logger.error("Failed to push signal to UI", e);
            }
        }
    }

    /**
     * Calculate comprehensive signal score based on multiple criteria
     */
    private SignalScore calculateSignalScore(String symbol, String side, double price,
            double rsi, double macd, double macdSignal,
            double ema50, double volume) {
        SignalScore score = new SignalScore(symbol, side, price);

        // 1. RSI Score (0-25 points)
        if ("BUY".equals(side)) {
            // Higher RSI = stronger bullish momentum
            // Best: RSI 75 (near overbought) = 25 points
            // Worst: RSI 55 (barely bullish) = 0 points
            score.rsiScore = Math.max(0, ((rsi - 55) / 20) * 25);
        } else { // SELL
            // Lower RSI = stronger bearish momentum
            // Best: RSI 25 (near oversold) = 25 points
            // Worst: RSI 45 (barely bearish) = 0 points
            score.rsiScore = Math.max(0, ((45 - rsi) / 20) * 25);
        }

        // 2. MACD Score (0-25 points)
        // Larger divergence = stronger signal
        double macdDivergence = Math.abs(macd - macdSignal);
        score.macdScore = Math.min(macdDivergence * 10000, 25); // Scale and cap

        // 3. Trend Score (0-20 points)
        MultiTimeframeAnalyzer.TrendDirection h1Trend = multiTfAnalyzer.get1HTrend(symbol);
        if ("BUY".equals(side)) {
            if (h1Trend == MultiTimeframeAnalyzer.TrendDirection.BULLISH) {
                score.trendScore = 20; // Perfect alignment
            } else if (h1Trend == MultiTimeframeAnalyzer.TrendDirection.NEUTRAL) {
                score.trendScore = 10; // Neutral okay
            }
        } else { // SELL
            if (h1Trend == MultiTimeframeAnalyzer.TrendDirection.BEARISH) {
                score.trendScore = 20;
            } else if (h1Trend == MultiTimeframeAnalyzer.TrendDirection.NEUTRAL) {
                score.trendScore = 10;
            }
        }

        // 4. Volume Score (0-15 points) - placeholder, need volume data
        // For now, give moderate score
        score.volumeScore = 10;

        // 5. BTC Score (0-10 points)
        if ("BUY".equals(side) && !"BEARISH".equals(btcTrend)) {
            score.btcScore = "BULLISH".equals(btcTrend) ? 10 : 5;
        } else if ("SELL".equals(side) && !"BULLISH".equals(btcTrend)) {
            score.btcScore = "BEARISH".equals(btcTrend) ? 10 : 5;
        }

        // 6. Depth Score (0-5 points) - placeholder
        score.depthScore = 5;

        score.calculateTotalScore();
        return score;
    }

    /**
     * Process batched signals and execute best ones
     */
    private void processBatchedSignals() {
        int batchSize = signalBatch.size();
        if (batchSize == 0) {
            return;
        }

        logger.info("🔄 Processing signal batch: {} signals collected", batchSize);

        // Get best signals above minimum score
        List<SignalScore> bestSignals = signalBatch.getSignalsAboveThreshold(minSignalScore, batchTopN);

        if (bestSignals.isEmpty()) {
            logger.info("⏸️ No signals above minimum score ({}) in this batch", minSignalScore);
            signalBatch.clear();
            return;
        }

        logger.info("🏆 Selected {} best signals from batch:", bestSignals.size());
        for (int i = 0; i < bestSignals.size(); i++) {
            SignalScore sig = bestSignals.get(i);
            logger.info("  {}. {}", i + 1, sig.toString());
        }

        // Execute entries for best signals
        for (SignalScore sig : bestSignals) {
            if (!riskManager.canOpenNewPosition()) {
                logger.info("⏸️ Cannot enter {} - risk limit reached", sig.symbol);
                continue;
            }

            logger.info("✅ Executing BEST signal: {} {}", sig.symbol, sig.side);
            executeEntry(sig.symbol, sig.side, sig.price);
        }

        signalBatch.clear();
    }

    public void shutdown() {
        stopAutomatedTrading();
    }
}
