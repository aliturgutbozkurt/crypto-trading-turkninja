package com.turkninja.engine;

/**
 * Market Regime Classification
 * 
 * Categorizes current market state to select appropriate trading strategy
 */
public enum MarketRegime {
    /**
     * Strong upward trend: ADX > 25, Price > EMA50, Positive EMA slope
     * Strategy: Aggressive trend-following
     */
    STRONG_UPTREND,

    /**
     * Weak/emerging upward trend: ADX 15-25, Price > EMA50
     * Strategy: Cautious trend-following
     */
    WEAK_UPTREND,

    /**
     * Strong downward trend: ADX > 25, Price < EMA50, Negative EMA slope
     * Strategy: Aggressive trend-following (shorts)
     */
    STRONG_DOWNTREND,

    /**
     * Weak/emerging downward trend: ADX 15-25, Price < EMA50
     * Strategy: Cautious trend-following (shorts)
     */
    WEAK_DOWNTREND,

    /**
     * Ranging market with high volatility: ADX < 15, ATR/Price > 2.5%
     * Strategy: Mean reversion with wider stops
     */
    RANGING_HIGH_VOL,

    /**
     * Ranging market with low volatility: ADX < 15, ATR/Price < 1.0%
     * Strategy: Mean reversion or skip (low opportunity)
     */
    RANGING_LOW_VOL,

    /**
     * Choppy/uncertain market: Conflicting signals
     * Strategy: Stay flat, manage existing positions
     */
    CHOPPY;

    public boolean isTrending() {
        return this == STRONG_UPTREND || this == WEAK_UPTREND ||
                this == STRONG_DOWNTREND || this == WEAK_DOWNTREND;
    }

    public boolean isRanging() {
        return this == RANGING_HIGH_VOL || this == RANGING_LOW_VOL;
    }

    public boolean isBullish() {
        return this == STRONG_UPTREND || this == WEAK_UPTREND;
    }

    public boolean isBearish() {
        return this == STRONG_DOWNTREND || this == WEAK_DOWNTREND;
    }

    public boolean isStrong() {
        return this == STRONG_UPTREND || this == STRONG_DOWNTREND;
    }
}
