package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.*;
import com.turkninja.infra.*;
import com.turkninja.infra.repository.AccountRepository;
import com.turkninja.infra.repository.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
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
    public TelegramNotifier telegramNotifier() {
        return new TelegramNotifier();
    }

    @Bean
    public RiskManager riskManager(FuturesBinanceService futuresBinanceService,
            FuturesWebSocketService webSocketService,
            OrderBookService orderBookService) {
        RiskManager manager = new RiskManager(null, futuresBinanceService, orderBookService);
        manager.setWebSocketService(webSocketService);
        return manager;
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
    public OrderBookService orderBookService() {
        return new OrderBookService();
    }

    @Bean
    public StrategyEngine strategyEngine(FuturesBinanceService futuresBinanceService,
            FuturesWebSocketService webSocketService, IndicatorService indicatorService, RiskManager riskManager,
            PositionTracker positionTracker, OrderBookService orderBookService,
            TelegramNotifier telegramNotifier) {
        return new StrategyEngine(webSocketService, futuresBinanceService, indicatorService, riskManager,
                positionTracker, orderBookService, telegramNotifier);
    }

    @Bean
    public SynchronizationService synchronizationService(FuturesBinanceService futuresBinanceService,
            AccountRepository accountRepository) {
        return new SynchronizationService(futuresBinanceService, accountRepository);
    }
}