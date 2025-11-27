package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.*;
import com.turkninja.infra.*;
import com.turkninja.web.service.WebSocketPushService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

@SpringBootApplication
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    public FuturesBinanceService futuresBinanceService() {
        FuturesBinanceService service = new FuturesBinanceService();
        boolean dryRun = Boolean.parseBoolean(Config.get(Config.DRY_RUN, "false"));
        service.setDryRun(dryRun);
        logger.warn("⚠️  TRADING MODE: DRY_RUN={}", dryRun);
        if (!dryRun) {
            logger.error("🚨 REAL TRADING ENABLED - REAL MONEY AT RISK! 🚨");
        }
        return service;
    }

    @Bean
    public FuturesWebSocketService futuresWebSocketService() {
        return new FuturesWebSocketService(
                Config.get(Config.BINANCE_API_KEY),
                Config.get(Config.BINANCE_SECRET_KEY));
    }

    @Bean
    public TelegramNotifier telegramNotifier() {
        return new TelegramNotifier();
    }

    @Bean
    public CorrelationService correlationService(FuturesBinanceService futuresBinanceService) {
        return new CorrelationService(futuresBinanceService);
    }

    @Bean
    public RiskManager riskManager(FuturesBinanceService futuresBinanceService,
            FuturesWebSocketService webSocketService,
            OrderBookService orderBookService,
            CorrelationService correlationService,
            InfluxDBService influxDBService,
            TelegramNotifier telegramNotifier,
            @Lazy WebSocketPushService webSocketPushService) {
        RiskManager manager = new RiskManager(null, futuresBinanceService, orderBookService, correlationService,
                influxDBService, telegramNotifier);
        manager.setWebSocketService(webSocketService);
        manager.setWebSocketPushService(webSocketPushService);
        return manager;
    }

    @Bean
    public PositionTracker positionTracker(RiskManager riskManager) {
        PositionTracker tracker = new PositionTracker(riskManager);
        // Circular dependency resolution: set tracker on risk manager
        riskManager.setPositionTracker(tracker);
        return tracker;
    }

    @Bean
    public IndicatorService indicatorService() {
        return new IndicatorService();
    }

    @Bean
    public OrderBookService orderBookService() {
        return new OrderBookService();
    }

    @Bean
    public InfluxDBService influxDBService() {
        return new InfluxDBService();
    }

    @Bean
    public StrategyEngine strategyEngine(FuturesBinanceService futuresBinanceService,
            FuturesWebSocketService webSocketService, IndicatorService indicatorService, RiskManager riskManager,
            PositionTracker positionTracker, OrderBookService orderBookService,
            TelegramNotifier telegramNotifier, InfluxDBService influxDBService) {
        return new StrategyEngine(webSocketService, futuresBinanceService, indicatorService, riskManager,
                positionTracker, orderBookService, telegramNotifier, influxDBService);
    }

    @Bean
    public CommandLineRunner startWebSocketService(FuturesWebSocketService webSocketService) {
        return args -> {
            // Start user data stream to populate cached account info
            webSocketService.startUserDataStream();
            logger.info("📡 FuturesWebSocketService user data stream started");
        };
    }

    @Bean
    public CommandLineRunner startStrategyEngine(StrategyEngine strategyEngine,
            FuturesWebSocketService webSocketService) {
        return args -> {
            // Start kline streams for trading symbols
            webSocketService.startKlineStream(strategyEngine.getTradingSymbols());
            logger.info("📊 Kline streams started for symbols: {}", strategyEngine.getTradingSymbols());

            // Start Mark Price Stream for real-time P&L updates
            webSocketService.startMarkPriceStream(strategyEngine.getTradingSymbols().toArray(new String[0]));
            logger.info("🏷️ Mark price streams started for symbols: {}", strategyEngine.getTradingSymbols());

            // Start automated trading
            strategyEngine.startAutomatedTrading();
            logger.info("🚀 Automated Trading Engine STARTED");
            logger.info("🎯 Monitoring: {}", strategyEngine.getTradingSymbols());
            logger.info("⚙️  Mode: {} (DRY_RUN={})",
                    Config.get("strategy.mode", "HYBRID"),
                    Config.get(Config.DRY_RUN, "false"));
        };
    }
}