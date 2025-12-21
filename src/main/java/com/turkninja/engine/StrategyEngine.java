package com.turkninja.engine;

import com.turkninja.engine.criteria.*;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.infra.TelegramNotifier;
import com.turkninja.infra.InfluxDBService;
import com.turkninja.model.optimizer.ParameterSet;
import com.turkninja.web.service.WebSocketPushService;
import com.turkninja.web.dto.SignalDTO;
import com.turkninja.config.Config;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

public class StrategyEngine {
    private static final Logger logger = LoggerFactory.getLogger(StrategyEngine.class);
    private final FuturesBinanceService futuresService;
    private final FuturesWebSocketService webSocketService;
    private final IndicatorService indicatorService;
    private final RiskManager riskManager;
    private final PositionTracker positionTracker;
    private final OrderBookService orderBookService;
    private final MultiTimeframeService multiTimeframeService; // MTF Analysis (Phase 2)
    private final AdaptiveParameterService adaptiveParamService; // Adaptive Parameters (Phase 1.1)
    private final KellyPositionSizer kellyPositionSizer; // Kelly Criterion Position Sizing (Phase 1.1)
    private final MarketRegimeDetector regimeDetector; // Hybrid Strategy: Market Regime Detection
    private final com.turkninja.engine.strategy.MeanReversionStrategy meanReversionStrategy; // Hybrid Strategy: Mean
                                                                                             // Reversion

    private final TelegramNotifier telegram;
    private final InfluxDBService influxDBService; // Time-series data storage
    private WebSocketPushService webSocketPushService; // Optional, for UI notifications

    // Trading symbols - Expanded to all major coins (per user request)
    // Top performers: AVAXUSDT (+1.55%), BTCUSDT (-1.72%), Others for
    // diversification
    private List<String> tradingSymbols = Arrays.asList(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "AVAXUSDT", "DOGEUSDT",
            "XRPUSDT", "MATICUSDT", "LTCUSDT", "ETCUSDT",
            "ASTERUSDT", "TAOUSDT");

    private ScheduledExecutorService tradingScheduler;
    private ScheduledExecutorService batchProcessor; // Processes batched signals
    private volatile boolean tradingActive = false;

    // Batch signal collection
    private final SignalBatch signalBatch = new SignalBatch();
    private boolean batchModeEnabled;
    private int batchTopN;
    private double minSignalScore;

    // Async order execution (Phase 1.2)
    private final ExecutorService orderExecutor;
    private boolean asyncExecutionEnabled = true; // Default to true for live trading

    // Strategy Filters (Phase 2 - Chain of Responsibility)
    private final List<StrategyCriteria> strategyFilters;

    // Concurrency control for Virtual Threads - prevents unbounded thread growth
    private final Semaphore analysisSemaphore = new Semaphore(5); // Max 5 concurrent analyses

    // Backtest Optimization
    private Map<String, org.ta4j.core.Indicator<org.ta4j.core.num.Num>> cachedIndicators;

    public StrategyEngine(FuturesWebSocketService webSocketService,
            FuturesBinanceService binanceService,
            IndicatorService indicatorService,
            RiskManager riskManager,
            PositionTracker positionTracker,
            OrderBookService orderBookService,
            TelegramNotifier telegramNotifier,
            InfluxDBService influxDBService) {
        this(webSocketService, binanceService, indicatorService, riskManager, positionTracker, orderBookService,
                telegramNotifier, influxDBService, new ParameterSet());
    }

    public StrategyEngine(FuturesWebSocketService webSocketService,
            FuturesBinanceService binanceService,
            IndicatorService indicatorService,
            RiskManager riskManager,
            PositionTracker positionTracker,
            OrderBookService orderBookService,
            TelegramNotifier telegramNotifier,
            InfluxDBService influxDBService,
            ParameterSet parameters) {
        this.webSocketService = webSocketService;
        this.futuresService = binanceService; // Renamed from binanceService to futuresService to match field
        this.indicatorService = indicatorService;
        this.riskManager = riskManager;
        this.positionTracker = positionTracker;
        this.orderBookService = orderBookService;
        this.telegram = telegramNotifier; // Renamed from telegramNotifier to telegram to match field
        this.influxDBService = influxDBService;

        this.multiTimeframeService = new MultiTimeframeService(webSocketService, indicatorService); // Kept
                                                                                                    // webSocketService
                                                                                                    // as per original
        this.adaptiveParamService = new AdaptiveParameterService(indicatorService);
        this.kellyPositionSizer = new KellyPositionSizer();

        // Initialize Hybrid Strategy components
        this.regimeDetector = new MarketRegimeDetector(indicatorService);
        this.meanReversionStrategy = new com.turkninja.engine.strategy.MeanReversionStrategy();

        // Initialize filters with parameters
        this.strategyFilters = new ArrayList<>();

        // ADX Filter
        this.strategyFilters.add(new ADXTrendStrengthFilter(
                (int) parameters.get("strategy.adx.period",
                        Double.parseDouble(Config.get("strategy.adx.period", "14"))),
                parameters.get("strategy.adx.min.strength",
                        Double.parseDouble(Config.get("strategy.adx.min.strength", "20")))));

        // EMA Slope Filter
        this.strategyFilters.add(new EMASlopeFilter(indicatorService,
                (int) parameters.get("strategy.ema.slope.period",
                        Double.parseDouble(Config.get("strategy.ema.slope.period", "50"))),
                (int) parameters.get("strategy.ema.slope.lookback",
                        Double.parseDouble(Config.get("strategy.ema.slope.lookback", "10"))),
                parameters.get("strategy.ema.slope.min.percent",
                        Double.parseDouble(Config.get("strategy.ema.slope.min.percent", "0.05")))));

        // EMA Alignment Filter
        this.strategyFilters.add(new EMAAlignmentFilter(
                parameters.get("strategy.ema.buffer.percent",
                        Double.parseDouble(Config.get("strategy.ema.buffer.percent", "0.007")))));

        // RSI Momentum Filter
        this.strategyFilters.add(new RSIMomentumFilter(adaptiveParamService,
                parameters.get("strategy.rsi.long.min", Double.parseDouble(Config.get("strategy.rsi.long.min", "50"))),
                parameters.get("strategy.rsi.long.max", Double.parseDouble(Config.get("strategy.rsi.long.max", "70"))),
                parameters.get("strategy.rsi.short.min",
                        Double.parseDouble(Config.get("strategy.rsi.short.min", "30"))),
                parameters.get("strategy.rsi.short.max",
                        Double.parseDouble(Config.get("strategy.rsi.short.max", "50")))));

        // MACD Confirmation Filter
        this.strategyFilters.add(new MACDConfirmationFilter(
                parameters.get("strategy.macd.signal.tolerance",
                        Double.parseDouble(Config.get("strategy.macd.signal.tolerance", "0.00001")))));

        // Volume Filter (No params yet, use default)
        this.strategyFilters.add(new VolumeConfirmationFilter(indicatorService)); // Added indicatorService as per
                                                                                  // original

        // Price Action Filter - FVG and Market Structure (Smart Money Concepts)
        this.strategyFilters.add(new PriceActionFilter(indicatorService));

        // Order Block Filter - Institutional Supply/Demand Zones (SMC)
        this.strategyFilters.add(new OrderBlockFilter(indicatorService));

        logger.info("✅ Strategy Engine initialized with {} filters", strategyFilters.size());
        logger.info("✅ Kelly Position Sizer initialized: enabled={}, hasSufficientHistory={}",
                kellyPositionSizer.isEnabled(), kellyPositionSizer.hasSufficientHistory());

        // Connect Kelly to PositionTracker for trade recording
        positionTracker.setKellyPositionSizer(kellyPositionSizer);

        // Initialize async order executor with Virtual Threads (Phase 1.2)
        this.orderExecutor = Executors.newVirtualThreadPerTaskExecutor();
        logger.info("✅ Async order executor initialized with Virtual Threads");

        // Load configurable entry filter parameters
        rsiLongMin = Integer.parseInt(Config.get("strategy.rsi.long.min", "50"));
        rsiLongMax = Integer.parseInt(Config.get("strategy.rsi.long.max", "78"));
        rsiShortMin = Integer.parseInt(Config.get("strategy.rsi.short.min", "22"));
        rsiShortMax = Integer.parseInt(Config.get("strategy.rsi.short.max", "50"));
        rsiBuyThreshold = Integer.parseInt(Config.get("strategy.rsi.buy.threshold", "30"));
        rsiSellThreshold = Integer.parseInt(Config.get("strategy.rsi.sell.threshold", "70"));

        logger.info(
                "Strategy Config Loaded: RSI [{}-{}] / [{}-{}]",
                rsiLongMin, rsiLongMax, rsiShortMin, rsiShortMax);

        // Batch signal selection config
        this.batchModeEnabled = Boolean.parseBoolean(Config.get("strategy.batch.enabled", "true"));
        this.batchTopN = Integer.parseInt(Config.get("strategy.batch.top.n", "3"));
        this.minSignalScore = Config.getDouble("strategy.signal.min.score", 50.0);
    }

    // Strategy Parameters (loaded from config)
    private final int rsiLongMin;
    private final int rsiLongMax;
    private final int rsiShortMin;
    private final int rsiShortMax;
    private final int rsiBuyThreshold;
    private final int rsiSellThreshold;

    // Note: RSI ranges are now dynamically calculated by AdaptiveParameterService
    // instead of static fields (Phase 1.1)

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
                    // Semaphore prevents unbounded thread growth during high-frequency periods
                    Thread.ofVirtual().start(() -> {
                        if (!analysisSemaphore.tryAcquire()) {
                            logger.warn("⚠️ Max concurrent analysis limit reached, skipping {}", symbol);
                            return;
                        }
                        try {
                            analyzeAndTrade(symbol);
                        } catch (Exception e) {
                            logger.error("Error in automated trading for " + symbol, e);
                        } finally {
                            analysisSemaphore.release();
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
            logger.info("🔵 BATCH MODE ENABLED - Initializing batch processor...");
            batchProcessor = Executors.newSingleThreadScheduledExecutor();
            int batchWindowSeconds = Integer.parseInt(Config.get("strategy.batch.window.seconds", "60"));

            logger.info("🔵 Scheduling batch processor: window={}s, topN={}, minScore={}",
                    batchWindowSeconds, batchTopN, minSignalScore);

            batchProcessor.scheduleAtFixedRate(() -> {
                try {
                    logger.info("⏰ BATCH TIMER TRIGGERED - Processing signals...");
                    processBatchedSignals();
                } catch (Exception e) {
                    logger.error("❌ Error processing batch signals", e);
                }
            }, batchWindowSeconds, batchWindowSeconds, TimeUnit.SECONDS);

            logger.info("✅ Batch signal processor started (window: {}s, top: {}, min score: {})",
                    batchWindowSeconds, batchTopN, minSignalScore);
        } else {
            logger.info("⚠️ Batch mode DISABLED - using immediate execution");
        }

        // Add a test signal to verify UI integration
        pushSignal("BTCUSDT", "INFO", "System Started - Monitoring markets for trading opportunities", 0.0, false,
                "SYSTEM");
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

    public RiskManager getRiskManager() {
        return riskManager;
    }

    public KellyPositionSizer getKellyPositionSizer() {
        return kellyPositionSizer;
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
        logger.info("🔎 analyzeAndTrade called for {}", symbol);

        if (!tradingActive) {
            logger.warn("⚠️ Trading NOT ACTIVE for {}", symbol);
            return;
        }

        try {
            // Fetch Data (5m only)
            List<JSONObject> klines5m = webSocketService.getCachedKlines(symbol, "5m", 100);

            if (klines5m.isEmpty()) {
                logger.warn("Insufficient cached klines for {} (5m: {}), skipping",
                        symbol, klines5m.size());
                return;
            }

            BarSeries series5m = convertCachedKlinesToBarSeries(symbol, klines5m);

            // Delegate to core logic
            analyzeAndTrade(symbol, series5m);

        } catch (Exception e) {
            logger.error("Error analyzing symbol: " + symbol, e);
        }
    }

    /**
     * Core strategy logic - accepts BarSeries for backtesting support
     */
    public void analyzeAndTrade(String symbol, BarSeries series5m) {
        String coinLogPrefix = "🔎";
        logger.info("{} analyzeAndTrade called for {}", coinLogPrefix, symbol);

        // Prevent trading on cooldown symbols (anti-reversal)
        if (hasActivePosition(symbol)) {
            logger.debug("⏸️ {} already has active position, skipping", symbol);
            return;
        }

        Long lastCloseTime = symbolCooldown.get(symbol);
        if (lastCloseTime != null && System.currentTimeMillis() - lastCloseTime < COOLDOWN_MILLIS) {
            logger.debug("⏸️ {} in cooldown, skipping analysis", symbol);
            return;
        }
        if (lastCloseTime != null) {
            symbolCooldown.remove(symbol);
        }

        // Start latency tracking
        long analysisStartTime = System.nanoTime();

        try {
            // ----- STEP 1: Calculate Indicators -----
            // Calculate indicators once (used by all filters and strategies)
            // Uses 5m data, hybrid strategy chooses between Trend-Following vs
            // Mean-Reversion
            Map<String, Double> indicators5m;

            if (cachedIndicators != null) {
                // Optimized path for backtest (O(N))
                indicators5m = new HashMap<>();
                int endIndex = series5m.getEndIndex();

                for (Map.Entry<String, org.ta4j.core.Indicator<org.ta4j.core.num.Num>> entry : cachedIndicators
                        .entrySet()) {
                    indicators5m.put(entry.getKey(), entry.getValue().getValue(endIndex).doubleValue());
                }

                // Add derived values that might be missing if not in cached set
                // (Currently getIndicators returns all needed, but safe to check)
            } else {
                // Standard path for live trading (recalculates)
                indicators5m = indicatorService.calculateIndicators(series5m);
            }

            double currentPrice = series5m.getLastBar().getClosePrice().doubleValue();

            // ----- STEP 2: Market Regime Detection -----
            MarketRegime regime = regimeDetector.detectRegime(symbol, series5m, indicators5m);
            double trendStrength = regimeDetector.getTrendStrength(regime, indicators5m.getOrDefault("ADX", 0.0));

            logger.info("{} | Price={} | Regime={} (Strength={:.0f}%)",
                    symbol, currentPrice, regime, trendStrength);

            // 5. Select Strategy
            String strategyMode = Config.get("strategy.mode", "HYBRID");
            boolean allowTrend = "HYBRID".equals(strategyMode) || "TREND_ONLY".equals(strategyMode);
            boolean allowRange = "HYBRID".equals(strategyMode) || "RANGE_ONLY".equals(strategyMode);

            if (allowTrend && (regime.isTrending() || regime == MarketRegime.WEAK_UPTREND
                    || regime == MarketRegime.WEAK_DOWNTREND)) {
                analyzeTrendFollowing(symbol, series5m, indicators5m, currentPrice, regime);
            } else if (allowRange && regime.isRanging()) {
                analyzeMeanReversion(symbol, series5m, indicators5m, currentPrice, regime);
            } else {
                logger.info("⏸️ {} Skipped - Regime {} not suitable for mode {}", symbol, regime, strategyMode);
            }

        } catch (Exception e) {
            logger.error("Error analyzing symbol: " + symbol, e);
        }
    }

    private void analyzeTrendFollowing(String symbol, BarSeries series5m, Map<String, Double> indicators5m,
            double currentPrice, MarketRegime regime) {
        // 5m Trend Indicators
        double ema50_5m = indicators5m.getOrDefault("EMA_50", currentPrice);
        double rsi_5m = indicators5m.getOrDefault("RSI", 50.0);
        double macd = indicators5m.getOrDefault("MACD", 0.0);
        double macdSignal = indicators5m.getOrDefault("MACD_SIGNAL", 0.0);

        // 1. Evaluate LONG Criteria
        boolean longPassed = true;
        String longFailReason = null;

        for (StrategyCriteria filter : strategyFilters) {
            if (!filter.evaluate(symbol, series5m, indicators5m, currentPrice, true)) {
                longPassed = false;
                longFailReason = filter.getFailureReason(symbol, series5m, indicators5m, currentPrice, true);
                logger.info("⏸️ {} LONG filtered by: {} Reason: {}", symbol, filter.getFilterName(), longFailReason);
                pushSignal(symbol, "BUY", "Blocked: " + longFailReason, currentPrice, false, "BLOCKED");
                break;
            }
        }

        if (longPassed) {
            if (btcTrend.equals("BEARISH")) {
                logger.info("⏸️ {} LONG filtered - BTC trend bearish", symbol);
                pushSignal(symbol, "BUY", "Blocked: BTC Trend Bearish", currentPrice, false, "BLOCKED");
                longPassed = false;
            } else if (!multiTimeframeService.allowLong(symbol)) {
                logger.info("⏸️ {} LONG filtered - MTF bearish", symbol);
                pushSignal(symbol, "BUY", "Blocked: MTF Bearish", currentPrice, false, "BLOCKED");
                longPassed = false;
            } else if (orderBookService != null && !orderBookService.confirmBuySignal(symbol, currentPrice)) {
                logger.info("⏸️ {} LONG filtered - OrderBook imbalance", symbol);
                pushSignal(symbol, "BUY", "Blocked: OrderBook Imbalance", currentPrice, false, "BLOCKED");
                longPassed = false;
            }
        }

        if (longPassed) {
            String buyReason = String.format("LONG (Trend): All filters passed. Regime=%s", regime);
            if (batchModeEnabled) {
                SignalScore score = calculateSignalScore(symbol, "BUY", currentPrice, rsi_5m, macd, macdSignal,
                        ema50_5m, 0);
                signalBatch.addSignal(score);
                return;
            }
            pushSignal(symbol, "BUY", buyReason, currentPrice, true, "PENDING");
            submitOrderAsync(symbol, "BUY", currentPrice, regime);
            return;
        }

        // 2. Evaluate SHORT Criteria
        boolean shortPassed = true;
        String shortFailReason = null;

        for (StrategyCriteria filter : strategyFilters) {
            if (!filter.evaluate(symbol, series5m, indicators5m, currentPrice, false)) {
                shortPassed = false;
                shortFailReason = filter.getFailureReason(symbol, series5m, indicators5m, currentPrice, false);
                logger.info("⏸️ {} SHORT filtered by: {} Reason: {}", symbol, filter.getFilterName(), shortFailReason);
                pushSignal(symbol, "SELL", "Blocked: " + shortFailReason, currentPrice, false, "BLOCKED");
                break;
            }
        }

        if (shortPassed) {
            if (btcTrend.equals("BULLISH")) {
                logger.info("⏸️ {} SHORT filtered - BTC trend bullish", symbol);
                pushSignal(symbol, "SELL", "Blocked: BTC Trend Bullish", currentPrice, false, "BLOCKED");
                shortPassed = false;
            } else if (!multiTimeframeService.allowShort(symbol)) {
                logger.info("⏸️ {} SHORT filtered - MTF bullish", symbol);
                pushSignal(symbol, "SELL", "Blocked: MTF Bullish", currentPrice, false, "BLOCKED");
                shortPassed = false;
            } else if (orderBookService != null && !orderBookService.confirmSellSignal(symbol, currentPrice)) {
                logger.info("⏸️ {} SHORT filtered - OrderBook imbalance", symbol);
                pushSignal(symbol, "SELL", "Blocked: OrderBook Imbalance", currentPrice, false, "BLOCKED");
                shortPassed = false;
            }
        }

        if (shortPassed) {
            String sellReason = String.format("SHORT (Trend): All filters passed. Regime=%s", regime);
            if (batchModeEnabled) {
                SignalScore score = calculateSignalScore(symbol, "SELL", currentPrice, rsi_5m, macd, macdSignal,
                        ema50_5m, 0);
                signalBatch.addSignal(score);
                return;
            }
            pushSignal(symbol, "SELL", sellReason, currentPrice, true, "PENDING");
            submitOrderAsync(symbol, "SELL", currentPrice, regime);
        }
    }

    private void analyzeMeanReversion(String symbol, BarSeries series5m, Map<String, Double> indicators5m,
            double currentPrice, MarketRegime regime) {
        // Mean Reversion Strategy
        if (meanReversionStrategy.checkLongEntry(symbol, series5m, indicators5m, currentPrice)) {
            String reason = String.format("LONG (MeanRev): BB Lower Touch + RSI Oversold. Regime=%s", regime);
            logger.info("🟢 MEAN REVERSION BUY: {}", reason);
            pushSignal(symbol, "BUY", reason, currentPrice, true, "PENDING");
            submitOrderAsync(symbol, "BUY", currentPrice, regime);
            return;
        }

        if (meanReversionStrategy.checkShortEntry(symbol, series5m, indicators5m, currentPrice)) {
            String reason = String.format("SHORT (MeanRev): BB Upper Touch + RSI Overbought. Regime=%s", regime);
            logger.info("🔴 MEAN REVERSION SELL: {}", reason);
            pushSignal(symbol, "SELL", reason, currentPrice, true, "PENDING");
            submitOrderAsync(symbol, "SELL", currentPrice, regime);
        }
    }

    /**
     * Submit order asynchronously (Phase 1.2)
     * Non-blocking execution using Virtual Threads
     */
    private void submitOrderAsync(String symbol, String side, double price, MarketRegime regime) {
        // SMART CONTRARIAN MODE: Reverse signals only in weak trends/ranging
        boolean contrarianEnabled = Boolean.parseBoolean(Config.get("strategy.contrarian.enabled", "false"));

        final String finalSide;
        if (contrarianEnabled && regime != null) {
            // Only reverse in weak trends or ranging markets, NOT in strong trends
            boolean isWeakTrend = regime == MarketRegime.WEAK_UPTREND ||
                    regime == MarketRegime.WEAK_DOWNTREND;
            boolean isRanging = regime.isRanging();

            if (isWeakTrend || isRanging) {
                String originalSide = side;
                finalSide = side.equals("BUY") ? "SELL" : "BUY";
                logger.info("🔄 SMART CONTRARIAN: Reversing {} → {} for {} (Regime: {})",
                        originalSide, finalSide, symbol, regime);
            } else {
                logger.info("✋ CONTRARIAN DISABLED for {} - Strong trend detected ({})", symbol, regime);
                finalSide = side;
            }
        } else {
            finalSide = side;
        }

        if (!asyncExecutionEnabled) {
            // Synchronous execution for backtesting
            try {
                executeEntry(symbol, finalSide, price, regime);
            } catch (Exception e) {
                logger.error("❌ Sync order failed for {} {}", symbol, side, e);
            }
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                executeEntry(symbol, finalSide, price, regime);
            } catch (Exception e) {
                logger.error("❌ Async order failed for {} {}", symbol, side, e);
                telegram.sendAlert(TelegramNotifier.AlertLevel.CRITICAL, String.format(
                        "⚠️ Order Failure\n%s %s @ %.2f\nError: %s",
                        symbol, side, price, e.getMessage()));
            }
        }, orderExecutor)
                .exceptionally(ex -> {
                    logger.error("❌ Uncaught async exception for {} {}", symbol, side, ex);
                    return null;
                });

        logger.debug("✅ {} {} order submitted async", symbol, side);
    }

    private void executeEntry(String symbol, String side, double price, MarketRegime regime) {
        try {
            // 1. Calculate position size
            double positionSize = calculatePositionSize(symbol, price, regime);

            // 2. Check correlation risk (NEW - Phase 1.1)
            if (!riskManager.checkCorrelationRisk(symbol, side)) {
                logger.warn("⏸️ {} {} blocked by correlation filter", symbol, side);
                return; // Don't enter - too correlated with existing positions
            }

            // 3. Place order
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

            // 3. Check Available Balance & Dynamic Sizing
            double availableBalance = futuresService.getAvailableBalance();
            if (availableBalance < 10.0) {
                logger.warn("⚠️ Insufficient balance (${}) to open position for {}",
                        String.format("%.2f", availableBalance), symbol);
                pushSignal(symbol, side, "Blocked: Low Balance (<$10)", price, false, "BLOCKED_BALANCE");
                return;
            }

            // If configured size > 95% of balance, reduce size
            if (positionSize > availableBalance * 0.95) {
                double newSize = availableBalance * 0.95;
                logger.info("⚠️ Adjusting position size for {}: ${} -> ${} (Balance: ${})",
                        symbol, positionSize, String.format("%.2f", newSize), String.format("%.2f", availableBalance));
                positionSize = newSize;
            }

            // 4. Check Order Book slippage
            if (orderBookService != null && !orderBookService.isSlippageAcceptable(symbol, side, positionSize, price)) {
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
            // 6. Place Order
            String orderResponse = futuresService.placeMarketOrder(symbol, side, quantity);

            // Check for failure
            if (orderResponse == null || orderResponse.contains("error")
                    || orderResponse.contains("Margin is insufficient")) {
                logger.error("❌ Order FAILED for {}: {}", symbol, orderResponse);
                pushSignal(symbol, side, "Order Failed: " + orderResponse, price, false, "FAILED");
                return; // STOP HERE - Do not track position or send Telegram
            }

            logger.info("✅ Order placed for {}: {} {} @ {}", symbol, quantity, side, price);

            // 7. Calculate Dynamic Stop Loss (if enabled)
            double stopLossPercent = calculateDynamicStopLoss(symbol, price);

            // 8. Track Position (Only if successful) with dynamic SL
            positionTracker.trackPosition(symbol, side, price, quantity, stopLossPercent);

            // 8. Record trade and position open to InfluxDB
            Instant now = java.time.Instant.now();
            String orderId = java.util.UUID.randomUUID().toString(); // Generate unique ID for deduplication
            if (influxDBService != null && influxDBService.isEnabled()) {
                influxDBService.writeTrade(symbol, side, price, quantity, positionSize, now, orderId);
                influxDBService.writePositionOpen(symbol, side, price, now, orderId); // Track entry time
            }

            // 9. Send Telegram Notification (Only if successful)
            if (telegram != null && telegram.isEnabled()) {
                telegram.notifyPositionOpened(symbol, side, price, quantity, positionSize);
            }

            // 10. Update Signal Status to EXECUTED
            pushSignal(symbol, side, "Trade Executed Successfully", price, true, "EXECUTED");

            // 11. Alert: Sound + Red console output
            System.out.print("\007"); // System beep
            // String redColor = "\u001B[31m"; // Unused
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

    private double calculatePositionSize(String symbol, double price, MarketRegime regime) {
        try {
            // Get available balance
            String accountJson = futuresService.getAccountInfo();
            JSONObject account = new JSONObject(accountJson);
            double availableBalance = account.getDouble("availableBalance");

            // Priority 1: Kelly Criterion (if enabled and has sufficient history)
            if (kellyPositionSizer.isEnabled() && kellyPositionSizer.hasSufficientHistory()) {
                double kellySize = kellyPositionSizer.getPositionSize(symbol, availableBalance);
                if (kellySize > 0) {
                    return kellySize;
                }
                // If Kelly fails, fall through to next method
            }

            // Priority 2: ATR-based sizing (Phase 2.1)
            boolean atrEnabled = Boolean.parseBoolean(Config.get("strategy.position.atr.enabled", "false"));
            if (atrEnabled) {
                return calculateATRBasedPositionSize(symbol, price, availableBalance);
            }

            // Priority 3: Fallback to fixed percentage
            double maxPercent = Config.getDouble("strategy.position.max_percent", 0.25);
            double positionSize = availableBalance * maxPercent;

            // Cap at risk manager's max position size
            double maxPositionSize = Config.getDouble("risk.max_position_size", 1000.0);
            positionSize = Math.min(positionSize, maxPositionSize);

            // Apply Regime Multiplier (Hybrid Strategy)
            if (regime != null) {
                double multiplier = regimeDetector.getPositionSizeMultiplier(regime);
                if (multiplier != 1.0) {
                    logger.info("⚖️ Adjusting position size for {}: ${} * {:.0f}% = ${}",
                            symbol, String.format("%.2f", positionSize), multiplier * 100,
                            String.format("%.2f", positionSize * multiplier));
                    positionSize *= multiplier;
                }
            }

            return positionSize;

        } catch (Exception e) {
            logger.error("Error calculating position size", e);
            return 0;
        }
    }

    /**
     * Calculate position size based on ATR (Average True Range) for risk
     * normalization (Phase 2.1)
     * Formula: Position Size = Risk Amount / (Stop Distance %)
     * This ensures every trade risks the same dollar amount regardless of
     * volatility
     */
    private double calculateATRBasedPositionSize(String symbol, double price, double balance) {
        try {
            // During backtests, BarSeries is passed via analyzeAndTrade, not globally
            // cached
            // ATR sizing requires access to the current backtest series
            // For now, disable during backtests and use percentage-based sizing
            // TODO: Pass BarSeries as parameter from BacktestEngine
            logger.debug("ATR sizing not available during backtest for {}, using percentage fallback", symbol);
            return balance * 0.25; // Fallback

        } catch (Exception e) {
            logger.error("Error in ATR position sizing for {}", symbol, e);
            return balance * 0.25; // Fallback
        }
    }

    private double calculateQuantity(String symbol, double positionSizeUsdt, double price) {
        // With 20x leverage, we can control 20x the position size
        // Using BigDecimal for precision to avoid LOT_SIZE errors from Binance
        BigDecimal notionalValue = BigDecimal.valueOf(positionSizeUsdt).multiply(BigDecimal.valueOf(20));
        BigDecimal quantityBD = notionalValue.divide(BigDecimal.valueOf(price), 10, java.math.RoundingMode.DOWN);

        // Round to appropriate precision based on Binance symbol rules
        // Reference: https://www.binance.com/en/futures/BTCUSDT (check "Quantity
        // Precision")
        // Dynamic Precision from Exchange Info
        int precision = futuresService.getQuantityPrecision(symbol);
        quantityBD = quantityBD.setScale(precision, java.math.RoundingMode.DOWN);

        return quantityBD.doubleValue();
    }

    private boolean hasActivePosition(String symbol) {
        return positionTracker.getPosition(symbol) != null;
    }

    public void analyze(String symbol) {
        try {
            // 1. Get Klines (5m)
            List<JSONObject> klines5m = webSocketService.getCachedKlines(symbol, "5m", 200);
            if (klines5m.size() < 50) {
                logger.warn("Not enough 5m data for {}: {}", symbol, klines5m.size());
                return;
            }

            // 2. Convert to BarSeries
            BarSeries series5m = convertCachedKlinesToBarSeries(symbol, klines5m);

            // 3. Calculate Indicators
            Map<String, Double> indicators5m = indicatorService.calculateIndicators(series5m);

            // 4. Extract Key Values
            double currentPrice = series5m.getLastBar().getClosePrice().doubleValue();
            double rsi_5m = indicators5m.getOrDefault("RSI", 50.0);
            double macd = indicators5m.getOrDefault("MACD", 0.0);
            double macdSignal = indicators5m.getOrDefault("MACD_SIGNAL", 0.0);
            double ema50_5m = indicators5m.getOrDefault("EMA_50", currentPrice);

            logger.debug("{} Analysis: Price={}, RSI={}, MACD={}, BB_Low={}", symbol, currentPrice, rsi_5m, macd,
                    indicators5m.getOrDefault("BB_LOWER", 0.0));

            if (rsi_5m < 30 && currentPrice < indicators5m.getOrDefault("BB_LOWER", 0.0)) {
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
                ZonedDateTime lastBarTime = series.getLastBar().getEndTime().atZone(ZoneId.of("UTC"));
                if (!time.isAfter(lastBarTime)) {
                    continue; // Skip duplicate or older bars
                }
            }

            try {
                indicatorService.addBar(series, time, open, high, low, close, volume);
            } catch (IllegalArgumentException e) {
                // Ignore duplicate bars or bars with time <= last bar time
                // This can happen if the check above fails due to time zone differences
            }
        }
        return series;
    }

    /**
     * Push trading signal to UI via WebSocket (if connected)
     */
    // Store recent signals for UI polling (in case WebSocket fails)
    private final List<SignalDTO> recentSignals = new java.util.concurrent.CopyOnWriteArrayList<>();

    public List<SignalDTO> getRecentSignals() {
        return recentSignals;
    }

    private void pushSignal(String symbol, String type, String reason, double price, boolean executed, String status) {
        // Create the signal DTO (Matches existing constructor: symbol, type, reason,
        // price, executed, status)
        SignalDTO signal = new SignalDTO(symbol, type, reason, price, executed, status);

        // Add to recent list (keep last 50)
        recentSignals.add(0, signal);
        if (recentSignals.size() > 50) {
            recentSignals.remove(recentSignals.size() - 1);
        }

        // Record signal to InfluxDB
        if (influxDBService != null && influxDBService.isEnabled()) {
            influxDBService.writeSignal(symbol, type, reason, price, executed, status, java.time.Instant.now());
        }

        if (webSocketPushService != null) {
            try {
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
            logger.info("✅ Executing BEST signal: {} {}", sig.symbol, sig.side);
            executeEntry(sig.symbol, sig.side, sig.price, null);
        }

        signalBatch.clear();
    }

    /**
     * Calculate dynamic stop loss based on ATR volatility
     * 
     * @param symbol       Symbol to calculate for
     * @param currentPrice Current market price
     * @return Stop loss percentage (e.g., 0.02 for 2%)
     */
    private double calculateDynamicStopLoss(String symbol, double currentPrice) {
        // Check if dynamic SL is enabled
        boolean dynamicSLEnabled = Boolean.parseBoolean(Config.get("risk.dynamic.sl.enabled", "false"));

        if (!dynamicSLEnabled) {
            // Use default static SL
            double baseSL = Config.getDouble("risk.dynamic.sl.base.percent", 0.02);
            logger.debug("{} Dynamic SL disabled, using base: {}%", symbol, baseSL * 100);
            return baseSL;
        }

        try {
            // Get 5m klines for ATR calculation
            List<JSONObject> klines5m = webSocketService.getCachedKlines(symbol, "5m", 50);

            if (klines5m.size() < 14) {
                logger.warn("{} Insufficient data for ATR, using base SL", symbol);
                return Config.getDouble("risk.dynamic.sl.base.percent", 0.02);
            }

            // Convert to BarSeries for ATR calculation
            BarSeries series = convertCachedKlinesToBarSeries(symbol, klines5m);

            // Calculate ATR (14 periods)
            org.ta4j.core.indicators.ATRIndicator atr = new org.ta4j.core.indicators.ATRIndicator(series, 14);
            double atrValue = atr.getValue(series.getEndIndex()).doubleValue();
            double atrPercent = (atrValue / currentPrice) * 100; // ATR as % of price

            // Load configuration
            double baseSL = Config.getDouble("risk.dynamic.sl.base.percent", 0.02);
            double highVolThreshold = Config.getDouble("risk.dynamic.sl.high.volatility.threshold", 3.0);
            double highVolMultiplier = Config.getDouble("risk.dynamic.sl.high.volatility.multiplier", 1.5);
            double lowVolThreshold = Config.getDouble("risk.dynamic.sl.low.volatility.threshold", 1.0);
            double lowVolMultiplier = Config.getDouble("risk.dynamic.sl.low.volatility.multiplier", 0.75);
            double minSL = Config.getDouble("risk.dynamic.sl.min.percent", 0.01);
            double maxSL = Config.getDouble("risk.dynamic.sl.max.percent", 0.05);

            // Calculate dynamic SL based on volatility
            double dynamicSL;
            if (atrPercent > highVolThreshold) {
                // High volatility - wider stop
                dynamicSL = baseSL * highVolMultiplier;
                logger.info("🔥 {} HIGH volatility detected (ATR: {}%) → SL: {}%",
                        symbol, String.format("%.2f", atrPercent), String.format("%.2f", dynamicSL * 100));
            } else if (atrPercent < lowVolThreshold) {
                // Low volatility - tighter stop
                dynamicSL = baseSL * lowVolMultiplier;
                logger.info("😴 {} LOW volatility detected (ATR: {}%) → SL: {}%",
                        symbol, String.format("%.2f", atrPercent), String.format("%.2f", dynamicSL * 100));
            } else {
                // Normal volatility - base stop
                dynamicSL = baseSL;
                logger.info("📊 {} NORMAL volatility (ATR: {}%) → SL: {}%",
                        symbol, String.format("%.2f", atrPercent), String.format("%.2f", dynamicSL * 100));
            }

            // Clamp to min/max
            dynamicSL = Math.max(minSL, Math.min(maxSL, dynamicSL));

            return dynamicSL;

        } catch (Exception e) {
            logger.error("{} Error calculating dynamic SL: {}", symbol, e.getMessage());
            return Config.getDouble("risk.dynamic.sl.base.percent", 0.02);
        }
    }

    public void setAsyncExecution(boolean enabled) {
        this.asyncExecutionEnabled = enabled;
        logger.info("Async execution set to: {}", enabled);
    }

    public void setCachedIndicators(Map<String, org.ta4j.core.Indicator<org.ta4j.core.num.Num>> cachedIndicators) {
        this.cachedIndicators = cachedIndicators;
    }

    public void shutdown() {
        stopAutomatedTrading();
    }
}
