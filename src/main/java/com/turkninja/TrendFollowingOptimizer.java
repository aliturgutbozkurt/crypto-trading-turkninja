package com.turkninja;

import com.turkninja.config.Config;
import com.turkninja.engine.*;
import com.turkninja.infra.*;
import com.turkninja.model.BacktestReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fine-tune Trend Following Profile
 * Starting from the best profile, test variations in risk/reward ratios
 */
public class TrendFollowingOptimizer {

    private static final Logger logger = LoggerFactory.getLogger(TrendFollowingOptimizer.class);

    public static void main(String[] args) {
        String symbol = "ETHUSDT";
        String startDate = "2024-10-26";
        String endDate = "2024-11-26";

        logger.info("╔══════════════════════════════════════════════╗");
        logger.info("║   TREND FOLLOWING FINE-TUNING                ║");
        logger.info("╚══════════════════════════════════════════════╝");
        logger.info("");
        logger.info("Testing variations of Trend Following strategy...");
        logger.info("");

        List<ParameterProfile> profiles = new ArrayList<>();

        // Base Trend Following parameters (from previous test)
        Map<String, String> base = new HashMap<>();
        base.put("strategy.rsi.long.min", "40");
        base.put("strategy.rsi.long.max", "65");
        base.put("strategy.rsi.short.min", "35");
        base.put("strategy.rsi.short.max", "60");
        base.put("strategy.adx.min.strength", "30");
        base.put("strategy.ema.slope.min.percent", "0.005");
        base.put("strategy.ema.buffer.percent", "0.005");

        // Variation 1: ORIGINAL (R:R = 2:1)
        Map<String, String> v1 = new HashMap<>(base);
        v1.put("risk.stop_loss_percent", "0.03");
        v1.put("risk.take_profit_percent", "0.06");
        v1.put("risk.trailing_stop_percent", "0.01");
        profiles.add(new ParameterProfile("V1_RR_2_1", v1));

        // Variation 2: Tighter stops (R:R = 2.5:1)
        Map<String, String> v2 = new HashMap<>(base);
        v2.put("risk.stop_loss_percent", "0.02");
        v2.put("risk.take_profit_percent", "0.05");
        v2.put("risk.trailing_stop_percent", "0.008");
        profiles.add(new ParameterProfile("V2_RR_2_5_1", v2));

        // Variation 3: Higher R:R (R:R = 3:1)
        Map<String, String> v3 = new HashMap<>(base);
        v3.put("risk.stop_loss_percent", "0.025");
        v3.put("risk.take_profit_percent", "0.075");
        v3.put("risk.trailing_stop_percent", "0.012");
        profiles.add(new ParameterProfile("V3_RR_3_1", v3));

        // Variation 4: Looser RSI (more trades)
        Map<String, String> v4 = new HashMap<>(base);
        v4.put("risk.stop_loss_percent", "0.025");
        v4.put("risk.take_profit_percent", "0.06");
        v4.put("risk.trailing_stop_percent", "0.01");
        v4.put("strategy.rsi.long.min", "35");
        v4.put("strategy.rsi.long.max", "70");
        v4.put("strategy.rsi.short.min", "30");
        v4.put("strategy.rsi.short.max", "65");
        profiles.add(new ParameterProfile("V4_LOOSER_RSI", v4));

        // Variation 5: Lower ADX threshold (more trades)
        Map<String, String> v5 = new HashMap<>(base);
        v5.put("risk.stop_loss_percent", "0.025");
        v5.put("risk.take_profit_percent", "0.06");
        v5.put("risk.trailing_stop_percent", "0.01");
        v5.put("strategy.adx.min.strength", "25");
        profiles.add(new ParameterProfile("V5_LOWER_ADX", v5));

        // Variation 6: Lower EMA slope (more trades)
        Map<String, String> v6 = new HashMap<>(base);
        v6.put("risk.stop_loss_percent", "0.025");
        v6.put("risk.take_profit_percent", "0.06");
        v6.put("risk.trailing_stop_percent", "0.01");
        v6.put("strategy.ema.slope.min.percent", "0.003");
        profiles.add(new ParameterProfile("V6_LOWER_SLOPE", v6));

        // Variation 7: Combination (looser filters + better R:R)
        Map<String, String> v7 = new HashMap<>(base);
        v7.put("risk.stop_loss_percent", "0.02");
        v7.put("risk.take_profit_percent", "0.06");
        v7.put("risk.trailing_stop_percent", "0.01");
        v7.put("strategy.adx.min.strength", "25");
        v7.put("strategy.ema.slope.min.percent", "0.003");
        v7.put("strategy.rsi.long.min", "35");
        v7.put("strategy.rsi.long.max", "70");
        profiles.add(new ParameterProfile("V7_COMBO", v7));

        // Run backtests
        List<ProfileResult> results = new ArrayList<>();

        try {
            Config.setProperty("strategy.batch.enabled", "false");
            Config.setProperty("strategy.mtf.enabled", "false");

            FuturesBinanceService realBinanceService = new FuturesBinanceService(true);
            IndicatorService indicatorService = new IndicatorService();

            for (int i = 0; i < profiles.size(); i++) {
                ParameterProfile profile = profiles.get(i);

                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                logger.info("Testing {}/{}: {}", i + 1, profiles.size(), profile.name);
                logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                for (Map.Entry<String, String> entry : profile.parameters.entrySet()) {
                    Config.setProperty(entry.getKey(), entry.getValue());
                }

                MockFuturesBinanceService mockService = new MockFuturesBinanceService(1000.0);
                FuturesWebSocketService mockWebSocketService = new FuturesWebSocketService("mockKey", "mockSecret") {
                    @Override
                    public void startUserDataStream() {
                    }

                    @Override
                    public void startKlineStream(List<String> symbols) {
                    }

                    @Override
                    public void startDepthStream(List<String> symbols) {
                    }
                };

                // Initialize Services
                RiskManager riskManager = new RiskManager(null, mockService, null,
                        new CorrelationService(realBinanceService), null, null); // null InfluxDB and TelegramNotifier
                PositionTracker positionTracker = new PositionTracker(riskManager);
                riskManager.setPositionTracker(positionTracker);

                TelegramNotifier mockTelegram = new TelegramNotifier() {
                    @Override
                    public void sendMessage(String message) {
                        // Suppress messages during optimization
                    }
                };

                // Initialize Strategy Engine
                StrategyEngine engine = new StrategyEngine(mockWebSocketService, mockService, indicatorService,
                        riskManager,
                        positionTracker, null, mockTelegram, null);

                engine.setAsyncExecution(false);

                BacktestEngine backtestEngine = new BacktestEngine(
                        engine, mockService, realBinanceService, indicatorService);

                BacktestReport report = backtestEngine.runBacktest(symbol, startDate, endDate, "5m");

                if (report != null) {
                    results.add(new ProfileResult(profile.name, report, profile.parameters));
                    logger.info("✅ Sharpe: {:.2f}, Win: {:.1f}%, Profit: {:.2f}%, Trades: {}",
                            report.sharpeRatio, report.winRate, report.getNetProfitPercent(), report.totalTrades);
                }

                logger.info("");
            }

            printComparison(results);

        } catch (Exception e) {
            logger.error("Optimization failed", e);
            System.exit(1);
        }
    }

    private static void printComparison(List<ProfileResult> results) {
        System.out.println("\n\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                  TREND FOLLOWING OPTIMIZATION RESULTS                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════╝");
        System.out.println("");

        System.out.printf("%-20s | %8s | %8s | %10s | %10s | %8s%n",
                "Variation", "Trades", "Win%", "Sharpe", "Profit", "Max DD");
        System.out.println("─────────────────────┼──────────┼──────────┼────────────┼────────────┼──────────");

        for (ProfileResult result : results) {
            BacktestReport r = result.report;
            System.out.printf("%-20s | %8d | %7.1f%% | %10.2f | %9.2f%% | %7.2f%%%n",
                    result.profileName,
                    r.totalTrades,
                    r.winRate,
                    r.sharpeRatio,
                    r.getNetProfitPercent(),
                    r.maxDrawdownPercent);
        }

        ProfileResult best = results.stream()
                .max((a, b) -> Double.compare(a.report.sharpeRatio, b.report.sharpeRatio))
                .orElse(null);

        if (best != null) {
            System.out.println("");
            System.out.println("🏆 BEST VARIATION: " + best.profileName);
            System.out.printf("   Sharpe: %.2f | Win Rate: %.1f%% | Profit: %.2f%% | Trades: %d%n",
                    best.report.sharpeRatio, best.report.winRate,
                    best.report.getNetProfitPercent(), best.report.totalTrades);

            System.out.println("\n📋 OPTIMAL PARAMETERS:");
            System.out.println("─────────────────────────────────────────");
            for (Map.Entry<String, String> entry : best.parameters.entrySet()) {
                System.out.printf("%-35s = %s%n", entry.getKey(), entry.getValue());
            }
        }

        System.out.println("");
    }

    static class ParameterProfile {
        String name;
        Map<String, String> parameters;

        ParameterProfile(String name, Map<String, String> parameters) {
            this.name = name;
            this.parameters = parameters;
        }
    }

    static class ProfileResult {
        String profileName;
        BacktestReport report;
        Map<String, String> parameters;

        ProfileResult(String profileName, BacktestReport report, Map<String, String> parameters) {
            this.profileName = profileName;
            this.report = report;
            this.parameters = parameters;
        }
    }
}
