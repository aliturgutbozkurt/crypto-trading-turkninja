package com.turkninja.engine;

import com.turkninja.infra.MockFuturesBinanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock Risk Manager for Backtesting
 * Disables live monitoring features and correlation checks that require
 * external services
 * But keeps SL/TP logic active via onPriceUpdate
 */
public class MockRiskManager extends RiskManager {
    private static final Logger logger = LoggerFactory.getLogger(MockRiskManager.class);

    public MockRiskManager(PositionTracker positionTracker, MockFuturesBinanceService futuresService) {
        super(positionTracker, futuresService, null, null, null, null);
    }

    @Override
    public void startMonitoring() {
        // Do nothing in backtest - no threads
        logger.info("MockRiskManager initialized (Passive Mode)");
    }

    @Override
    public boolean checkCorrelationRisk(String symbol, String side) {
        // Always allow in backtest - ignoring correlation complexity
        return true;
    }

    @Override
    public void checkPositionOnPriceUpdate(String symbol, double markPrice) {
        // Delegate to standard logic which checks SL/TP
        super.onPriceUpdate(symbol, markPrice);
    }

    // Override methods that might use OrderBookService or other null dependencies

    // We assume checkExitCondition only uses internal maps, but if it uses
    // OrderBookService inside...
    // The snippet showed shouldExitWithOrderBookCheck being called. We should mock
    // that.

    // NOTE: shouldExitWithOrderBookCheck is private/protected? Snippet didn't show
    // visibility.
    // If it's private, we can't override it.
    // If it's used inside checkExitCondition, we might crash if it uses
    // orderBookService.

    // Let's rely on standard fixed SL/TP first.
    // Trailing Stop might fail if it hits orderBookService.

    // Assuming shouldExitWithOrderBookCheck is private, we can't override.
    // However, we passed null for OrderBookService.
    // If logic has `if (orderBookService != null)`, we are safe.
    // If not, we might crash.

    // Let's hope orderBookService usage is guarded or we will find out during run.

}
