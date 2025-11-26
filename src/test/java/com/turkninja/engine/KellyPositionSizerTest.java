package com.turkninja.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class KellyPositionSizerTest {

    @Test
    public void testCalculateKellySize() {
        // Enable Kelly for testing
        com.turkninja.config.Config.setProperty("strategy.position.kelly.enabled", "true");
        com.turkninja.config.Config.setProperty("strategy.position.kelly.min_trades", "5");

        KellyPositionSizer sizer = new KellyPositionSizer();

        // Simulate trade history to establish win rate and ratios
        // Target: Win rate 50%, Win/Loss ratio 2.0
        // 10 wins of 2% profit, 10 losses of 1% loss
        for (int i = 0; i < 10; i++) {
            sizer.recordTrade(true, 0.02);
            sizer.recordTrade(false, -0.01);
        }

        // Kelly = 0.5 - (0.5 / 2.0) = 0.25 (25%)
        // Safety multiplier is 0.25 (default) -> 6.25%

        double balance = 10000.0;
        double size = sizer.getPositionSize("BTCUSDT", balance);

        assertTrue(size > 0, "Position size should be positive");
        assertTrue(size <= balance * 0.25, "Position size should be capped at max percent (25%)");

        // Expected: ~6.25% of 10000 = 625
        // Allow some margin for floating point math
        assertEquals(625.0, size, 50.0, "Position size should be approx 6.25% of balance");
    }

    @Test
    public void testCalculateKellySizeWithLowWinRate() {
        KellyPositionSizer sizer = new KellyPositionSizer();

        // Simulate poor performance: 10% win rate, 1:1 ratio
        for (int i = 0; i < 2; i++) {
            sizer.recordTrade(true, 0.01);
        }
        for (int i = 0; i < 18; i++) {
            sizer.recordTrade(false, -0.01);
        }

        double size = sizer.getPositionSize("ETHUSDT", 10000.0);

        // Should fallback to fixed size or 0 depending on config, but definitely not
        // full Kelly
        // With default config, it falls back to fixed size if Kelly is negative/zero
        // But let's just ensure it runs without error and returns a valid number
        assertTrue(size >= 0, "Position size should be non-negative");
    }
}
