package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.IndicatorService;
import com.turkninja.engine.PositionTracker;
import com.turkninja.engine.RiskManager;
import com.turkninja.engine.StrategyEngine;
import com.turkninja.infra.*;
import com.turkninja.infra.repository.AccountRepository;
import com.turkninja.infra.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }

    @Bean
    public DatabaseService databaseService() {
        return new DatabaseService(
                Config.get(Config.MONGODB_URI, "mongodb://localhost:27017"),
                Config.get(Config.DB_NAME, "crypto_trading"));
    }

    @Bean
    public AccountRepository accountRepository(DatabaseService databaseService) {
        return new AccountRepository(databaseService);
    }

    @Bean
    public TradeRepository tradeRepository(DatabaseService databaseService) {
        return new TradeRepository(databaseService);
    }

    @Bean
    public FuturesBinanceService futuresBinanceService() {
        FuturesBinanceService service = new FuturesBinanceService();
        boolean dryRun = Boolean.parseBoolean(Config.get(Config.DRY_RUN, "false"));
        service.setDryRun(dryRun);
        return service;
    }

    @Bean
    public FuturesWebSocketService futuresWebSocketService() {
        return new FuturesWebSocketService(
                Config.get(Config.BINANCE_API_KEY),
                Config.get(Config.BINANCE_SECRET_KEY));
    }

    @Bean
    public RiskManager riskManager(FuturesBinanceService futuresBinanceService) {
        // RiskManager needs PositionTracker, but PositionTracker needs RiskManager.
        // We initialize with null PositionTracker first, then set it later.
        // Or better, we can rely on Spring's singleton nature and set it in the
        // PositionTracker bean or a separate config.
        // Here we follow the original pattern: create with null, then set.
        // But since beans are immutable-ish in definition, we need a way to link them.
        // PositionTracker constructor takes RiskManager.
        // RiskManager has setPositionTracker.
        return new RiskManager(null, futuresBinanceService);
    }

    @Bean
    public PositionTracker positionTracker(TradeRepository tradeRepository, RiskManager riskManager) {
        PositionTracker tracker = new PositionTracker(tradeRepository, riskManager);
        // Circular dependency resolution: set tracker on risk manager
        riskManager.setPositionTracker(tracker);
        return tracker;
    }

    @Bean
    public IndicatorService indicatorService() {
        return new IndicatorService();
    }

    @Bean
    public StrategyEngine strategyEngine(FuturesBinanceService futuresBinanceService,
            FuturesWebSocketService webSocketService,
            IndicatorService indicatorService,
            RiskManager riskManager,
            PositionTracker positionTracker) {
        return new StrategyEngine(futuresBinanceService, webSocketService,
                indicatorService, riskManager, positionTracker);
    }

    @Bean
    public SynchronizationService synchronizationService(FuturesBinanceService futuresBinanceService,
            AccountRepository accountRepository) {
        return new SynchronizationService(futuresBinanceService, accountRepository);
    }
}