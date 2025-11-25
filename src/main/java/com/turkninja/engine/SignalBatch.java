package com.turkninja.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Collects trading signals in a batch window and provides ranking
 * functionality.
 * Allows selecting the best N signals based on total score.
 */
public class SignalBatch {
    private static final Logger logger = LoggerFactory.getLogger(SignalBatch.class);

    private final List<SignalScore> signals = new CopyOnWriteArrayList<>();
    private long batchStartTime;

    public SignalBatch() {
        this.batchStartTime = System.currentTimeMillis();
    }

    /**
     * Add a signal to the current batch
     */
    public void addSignal(SignalScore signal) {
        signals.add(signal);
        logger.info("📊 Signal added to batch: {}", signal);
    }

    /**
     * Get the best N signal s sorted by total score (descending)
     */
    public List<SignalScore> getBestSignals(int limit) {
        return signals.stream()
                .sorted() // Uses compareTo (highest score first)
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get all signals above a minimum score threshold
     */
    public List<SignalScore> getSignalsAboveThreshold(double minScore, int limit) {
        return signals.stream()
                .filter(s -> s.totalScore >= minScore)
                .sorted()
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Get signal count in current batch
     */
    public int size() {
        return signals.size();
    }

    /**
     * Clear all signals and reset batch window
     */
    public void clear() {
        if (!signals.isEmpty()) {
            logger.info("🧹 Clearing batch with {} signals", signals.size());
        }
        signals.clear();
        batchStartTime = System.currentTimeMillis();
    }

    /**
     * Get batch age in seconds
     */
    public long getAgeSeconds() {
        return (System.currentTimeMillis() - batchStartTime) / 1000;
    }
}
