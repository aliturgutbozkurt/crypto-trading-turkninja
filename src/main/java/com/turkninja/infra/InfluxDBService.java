package com.turkninja.infra;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.QueryApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.turkninja.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * InfluxDB Service for Time Series Data Storage
 * 
 * Stores:
 * - OHLCV/Kline data (real-time market data)
 * - Trade executions
 * - Performance metrics
 * - Strategy signals
 */
@Component
public class InfluxDBService {
    private static final Logger logger = LoggerFactory.getLogger(InfluxDBService.class);

    private final InfluxDBClient client;
    private final WriteApiBlocking writeApi;
    private final QueryApi queryApi;
    private boolean enabled;
    private final String bucket;
    private final String org;

    public InfluxDBService() {
        this.enabled = Boolean.parseBoolean(Config.get("INFLUXDB_ENABLED", "false"));

        if (!enabled) {
            logger.info("InfluxDB disabled - time series data will not be stored");
            this.client = null;
            this.writeApi = null;
            this.queryApi = null;
            this.bucket = null;
            this.org = null;
            return;
        }

        String url = Config.get("INFLUXDB_URL", "http://localhost:8086");
        String token = Config.get("INFLUXDB_TOKEN", "");
        this.org = Config.get("INFLUXDB_ORG", "turkninja");
        this.bucket = Config.get("INFLUXDB_BUCKET", "trading_data");

        if (token.isEmpty()) {
            logger.warn("⚠️ InfluxDB token not configured - time series storage disabled");
            this.client = null;
            this.writeApi = null;
            this.queryApi = null;
            this.enabled = false;
            return;
        }

        try {
            this.client = InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket);
            this.writeApi = client.getWriteApiBlocking();
            this.queryApi = client.getQueryApi();

            // Test connection
            client.ping();

            logger.info("✅ InfluxDB connected: {} (org: {}, bucket: {})", url, org, bucket);
        } catch (Exception e) {
            logger.error("❌ Failed to connect to InfluxDB: {}", e.getMessage());
            throw new RuntimeException("InfluxDB connection failed", e);
        }
    }

    /**
     * Write OHLCV/Kline data
     */
    public void writeKline(String symbol, String interval, double open, double high,
            double low, double close, double volume, Instant timestamp) {
        if (!enabled)
            return;

        try {
            Point point = Point.measurement("klines")
                    .addTag("symbol", symbol)
                    .addTag("interval", interval)
                    .addField("open", open)
                    .addField("high", high)
                    .addField("low", low)
                    .addField("close", close)
                    .addField("volume", volume)
                    .time(timestamp, WritePrecision.MS);

            writeApi.writePoint(point);

            if (Math.random() < 0.01) { // Log 1% of writes to avoid spam
                logger.debug("📊 Kline written: {} {} @ {}", symbol, interval, close);
            }
        } catch (Exception e) {
            logger.error("Failed to write kline for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Write trade execution
     */
    public void writeTrade(String symbol, String side, double entryPrice, double quantity,
            double positionSizeUsdt, Instant timestamp, String orderId) {
        if (!enabled)
            return;

        try {
            Point point = Point.measurement("trades")
                    .addTag("symbol", symbol)
                    .addTag("side", side)
                    .addTag("order_id", orderId) // Unique ID to prevent duplicates
                    .addField("entry_price", entryPrice)
                    .addField("quantity", quantity)
                    .addField("position_size_usdt", positionSizeUsdt)
                    .addField("notional_value", quantity * entryPrice)
                    .time(timestamp, WritePrecision.MS);

            writeApi.writePoint(point);
            logger.info("📝 Trade written to InfluxDB: {} {} @ ${}", symbol, side, entryPrice);
        } catch (Exception e) {
            logger.error("Failed to write trade for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Write position open event (for entry time tracking)
     */
    public void writePositionOpen(String symbol, String side, double entryPrice, Instant timestamp, String orderId) {
        if (!enabled)
            return;

        try {
            Point point = Point.measurement("position_opens")
                    .addTag("symbol", symbol)
                    .addTag("side", side)
                    .addTag("order_id", orderId) // Unique ID
                    .addField("entry_price", entryPrice)
                    .addField("entry_time_ms", timestamp.toEpochMilli())
                    .time(timestamp, WritePrecision.MS);

            writeApi.writePoint(point);
            logger.debug("📝 Position open written: {} {} @ ${}", symbol, side, entryPrice);
        } catch (Exception e) {
            logger.error("Failed to write position open for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Get entry time for an open position from InfluxDB
     * Returns null if not found
     */
    public Long getPositionEntryTime(String symbol) {
        if (!enabled)
            return null;

        try {
            // Query the most recent position_opens entry for this symbol
            String flux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -30d) " +
                            "|> filter(fn: (r) => r._measurement == \"position_opens\") " +
                            "|> filter(fn: (r) => r.symbol == \"%s\") " +
                            "|> filter(fn: (r) => r._field == \"entry_time_ms\") " +
                            "|> last()",
                    bucket, symbol);

            List<FluxTable> tables = queryApi.query(flux, org);

            if (!tables.isEmpty() && !tables.get(0).getRecords().isEmpty()) {
                Object value = tables.get(0).getRecords().get(0).getValue();
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            }

            return null;
        } catch (Exception e) {
            logger.error("Failed to query entry time for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Write position close event
     */
    public void writePositionClose(String symbol, String side, double entryPrice, double exitPrice, double quantity,
            double pnl, double durationSeconds, String reason, Instant timestamp, String orderId) {
        if (!enabled)
            return;

        try {
            Point point = Point.measurement("position_closes")
                    .addTag("symbol", symbol)
                    .addTag("side", side)
                    .addTag("reason", reason)
                    .addTag("order_id", orderId) // Unique ID
                    .addField("entry_price", entryPrice)
                    .addField("exit_price", exitPrice)
                    .addField("quantity", quantity)
                    .addField("pnl", pnl)
                    .addField("duration_seconds", durationSeconds)
                    .time(timestamp, WritePrecision.MS);

            writeApi.writePoint(point);
            logger.info("📝 Position close written: {} {} PnL: ${}", symbol, side, String.format("%.2f", pnl));
        } catch (Exception e) {
            logger.error("Failed to write position close for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Write performance metrics
     */
    public void writeMetric(String metricName, Map<String, String> tags, Map<String, Object> fields) {
        if (!enabled)
            return;

        try {
            Point point = Point.measurement("metrics")
                    .addTag("metric_name", metricName);

            // Add custom tags
            if (tags != null) {
                tags.forEach(point::addTag);
            }

            // Add fields
            if (fields != null) {
                fields.forEach((key, value) -> {
                    if (value instanceof Number) {
                        point.addField(key, ((Number) value).doubleValue());
                    } else if (value instanceof String) {
                        point.addField(key, (String) value);
                    } else if (value instanceof Boolean) {
                        point.addField(key, (Boolean) value);
                    }
                });
            }

            point.time(Instant.now(), WritePrecision.MS);
            writeApi.writePoint(point);

            logger.debug("📊 Metric written: {}", metricName);
        } catch (Exception e) {
            logger.error("Failed to write metric {}: {}", metricName, e.getMessage());
        }
    }

    /**
     * Write strategy signal
     */
    public void writeSignal(String symbol, String type, String reason, double price,
            boolean executed, String status, Instant timestamp) {
        if (!enabled)
            return;

        try {
            Point point = Point.measurement("signals")
                    .addTag("symbol", symbol)
                    .addTag("type", type)
                    .addTag("status", status)
                    .addField("price", price)
                    .addField("executed", executed)
                    .addField("reason", reason)
                    .time(timestamp, WritePrecision.MS);

            writeApi.writePoint(point);

            if (executed) {
                logger.debug("📡 Signal written: {} {} @ ${} ({})", symbol, type, price, status);
            }
        } catch (Exception e) {
            logger.error("Failed to write signal for {}: {}", symbol, e.getMessage());
        }
    }

    /**
     * Query klines for a symbol in a time range
     */
    public List<Map<String, Object>> queryKlines(String symbol, Instant start, Instant end) {
        if (!enabled)
            return new ArrayList<>();

        try {
            String flux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: %s, stop: %s) " +
                            "|> filter(fn: (r) => r._measurement == \"klines\") " +
                            "|> filter(fn: (r) => r.symbol == \"%s\")",
                    bucket, start, end, symbol);

            List<FluxTable> tables = queryApi.query(flux, org);
            List<Map<String, Object>> results = new ArrayList<>();

            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    results.add(record.getValues());
                }
            }

            return results;
        } catch (Exception e) {
            logger.error("Failed to query klines for {}: {}", symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get trade count in time range
     */
    public long getTradeCount(Instant start, Instant end) {
        if (!enabled)
            return 0;

        try {
            String flux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: %s, stop: %s) " +
                            "|> filter(fn: (r) => r._measurement == \"trades\") " +
                            "|> count()",
                    bucket, start, end);

            List<FluxTable> tables = queryApi.query(flux, org);

            if (!tables.isEmpty() && !tables.get(0).getRecords().isEmpty()) {
                Object value = tables.get(0).getRecords().get(0).getValue();
                return value instanceof Number ? ((Number) value).longValue() : 0;
            }

            return 0;
        } catch (Exception e) {
            logger.error("Failed to query trade count: {}", e.getMessage());
            return 0;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Query recent trade history (closed positions)
     * Returns paginated list of closed trades with PnL, reason, etc.
     */
    public List<com.turkninja.web.dto.TradeHistoryDTO> queryRecentTrades(int limit, int offset) {
        if (!enabled)
            return new ArrayList<>();

        try {
            // Query closed positions ordered by time DESC with pagination
            // Use pivot to get all fields in one row per trade
            String flux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -30d) " + // Last 30 days
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> pivot(rowKey:[\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\") " +
                            "|> sort(columns: [\"_time\"], desc: true) " +
                            "|> limit(n: %d, offset: %d)",
                    bucket, limit, offset);

            List<FluxTable> tables = queryApi.query(flux, org);
            List<com.turkninja.web.dto.TradeHistoryDTO> trades = new ArrayList<>();

            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    String symbol = (String) record.getValueByKey("symbol");
                    String side = (String) record.getValueByKey("side");
                    String reason = (String) record.getValueByKey("reason");
                    Instant timestamp = record.getTime();

                    // Extract fields - after pivot they are direct columns
                    double entryPrice = 0;
                    double exitPrice = 0;
                    double pnl = 0;
                    long durationSeconds = 0;

                    Object entryPriceObj = record.getValueByKey("entry_price");
                    Object exitPriceObj = record.getValueByKey("exit_price");
                    Object pnlObj = record.getValueByKey("pnl");
                    Object durationObj = record.getValueByKey("duration_seconds");

                    if (entryPriceObj != null)
                        entryPrice = ((Number) entryPriceObj).doubleValue();
                    if (exitPriceObj != null)
                        exitPrice = ((Number) exitPriceObj).doubleValue();
                    if (pnlObj != null)
                        pnl = ((Number) pnlObj).doubleValue();
                    if (durationObj != null)
                        durationSeconds = ((Number) durationObj).longValue();

                    // Calculate P&L percentage from entry and exit prices
                    double pnlPercent = 0;
                    if (entryPrice > 0 && exitPrice > 0) {
                        if (side.equalsIgnoreCase("BUY")) {
                            // LONG: profit when exit > entry
                            pnlPercent = ((exitPrice - entryPrice) / entryPrice) * 100.0;
                        } else {
                            // SHORT: profit when entry > exit
                            pnlPercent = ((entryPrice - exitPrice) / entryPrice) * 100.0;
                        }
                    }

                    com.turkninja.web.dto.TradeHistoryDTO trade = new com.turkninja.web.dto.TradeHistoryDTO(
                            symbol, side, entryPrice, exitPrice,
                            pnl, pnlPercent, reason,
                            durationSeconds, timestamp, "CLOSED");

                    trades.add(trade);
                }
            }

            logger.debug("📊 Queried {} closed trades (limit: {}, offset: {})", trades.size(), limit, offset);
            return trades;

        } catch (Exception e) {
            logger.error("Failed to query recent trades: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Get total count of closed trades
     */
    public long getTotalTradeCount() {
        if (!enabled)
            return 0;

        try {
            String flux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -30d) " +
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> count()",
                    bucket);

            List<FluxTable> tables = queryApi.query(flux, org);

            if (!tables.isEmpty() && !tables.get(0).getRecords().isEmpty()) {
                Object value = tables.get(0).getRecords().get(0).getValue();
                return value instanceof Number ? ((Number) value).longValue() : 0;
            }

            return 0;
        } catch (Exception e) {
            logger.error("Failed to query total trade count: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Get aggregate metrics (Total PnL, Win Rate, Total Trades, and advanced stats)
     */
    public Map<String, Object> getAggregateMetrics() {
        if (!enabled) {
            return Map.of(
                    "totalPnL", 0.0,
                    "winRate", 0.0,
                    "totalTrades", 0L,
                    "winningTrades", 0L,
                    "avgTrade", 0.0,
                    "bestTrade", 0.0,
                    "worstTrade", 0.0,
                    "maxDrawdown", 0.0,
                    "sharpeRatio", 0.0);
        }

        try {
            // Calculate Total PnL (all-time)
            String pnlFlux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -365d) " + // Last year
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> filter(fn: (r) => r._field == \"pnl\") " +
                            "|> sum()",
                    bucket);

            // Calculate Wins and Total Trades
            // Instead of using 'win' field (which may be missing in old data),
            // count all trades and derive wins from pnl > 0
            String countFlux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -365d) " +
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> filter(fn: (r) => r._field == \"pnl\") " +
                            "|> count()",
                    bucket);

            String winsFlux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -365d) " +
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> filter(fn: (r) => r._field == \"pnl\") " +
                            "|> filter(fn: (r) => r._value > 0) " +
                            "|> count()",
                    bucket);

            // Calculate Max/Min PnL (Best and Worst trades)
            String maxPnlFlux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -365d) " +
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> filter(fn: (r) => r._field == \"pnl\") " +
                            "|> max()",
                    bucket);

            String minPnlFlux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -365d) " +
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> filter(fn: (r) => r._field == \"pnl\") " +
                            "|> min()",
                    bucket);

            double totalPnL = 0.0;
            long totalTrades = 0;
            long winningTrades = 0;
            double bestTrade = 0.0;
            double worstTrade = 0.0;

            // Execute PnL query
            List<FluxTable> pnlTables = queryApi.query(pnlFlux, org);
            if (!pnlTables.isEmpty() && !pnlTables.get(0).getRecords().isEmpty()) {
                Object val = pnlTables.get(0).getRecords().get(0).getValue();
                if (val instanceof Number) {
                    totalPnL = ((Number) val).doubleValue();
                }
            }

            // Execute Count query
            List<FluxTable> countTables = queryApi.query(countFlux, org);
            if (!countTables.isEmpty() && !countTables.get(0).getRecords().isEmpty()) {
                Object val = countTables.get(0).getRecords().get(0).getValue();
                if (val instanceof Number) {
                    totalTrades = ((Number) val).longValue();
                }
            }

            // Execute Wins query
            List<FluxTable> winsTables = queryApi.query(winsFlux, org);
            if (!winsTables.isEmpty() && !winsTables.get(0).getRecords().isEmpty()) {
                Object val = winsTables.get(0).getRecords().get(0).getValue();
                if (val instanceof Number) {
                    winningTrades = ((Number) val).longValue();
                }
            }

            // Execute Max PnL query
            List<FluxTable> maxTables = queryApi.query(maxPnlFlux, org);
            if (!maxTables.isEmpty() && !maxTables.get(0).getRecords().isEmpty()) {
                Object val = maxTables.get(0).getRecords().get(0).getValue();
                if (val instanceof Number) {
                    bestTrade = ((Number) val).doubleValue();
                }
            }

            // Execute Min PnL query
            List<FluxTable> minTables = queryApi.query(minPnlFlux, org);
            if (!minTables.isEmpty() && !minTables.get(0).getRecords().isEmpty()) {
                Object val = minTables.get(0).getRecords().get(0).getValue();
                if (val instanceof Number) {
                    worstTrade = ((Number) val).doubleValue();
                }
            }

            double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100.0 : 0.0;
            double avgTrade = totalTrades > 0 ? totalPnL / totalTrades : 0.0;

            // Simplified Sharpe Ratio estimation (assumes 0 risk-free rate, needs std dev)
            // For now, just a placeholder - we should calculate from trade returns
            double sharpeRatio = 0.0;
            if (totalTrades > 0 && avgTrade != 0 && worstTrade < 0) {
                // Rough approximation: sharpe = avgReturn / stdDev
                // We'd need to query all PnL values for proper calculation
                // For now, simple heuristic
                sharpeRatio = (avgTrade / Math.abs(worstTrade));
            }

            // Max Drawdown - simplified (would need cumulative PnL series for accurate
            // calculation)
            double maxDrawdown = worstTrade < 0 ? Math.abs(worstTrade) : 0.0;

            logger.debug("📊 Metrics calculated: totalPnL={}, trades={}, wins={}, winRate={}%, best={}, worst={}",
                    totalPnL, totalTrades, winningTrades, winRate, bestTrade, worstTrade);

            return Map.of(
                    "totalPnL", totalPnL,
                    "winRate", winRate,
                    "totalTrades", totalTrades,
                    "winningTrades", winningTrades,
                    "avgTrade", avgTrade,
                    "bestTrade", bestTrade,
                    "worstTrade", worstTrade,
                    "maxDrawdown", maxDrawdown,
                    "sharpeRatio", sharpeRatio);

        } catch (Exception e) {
            logger.error("Failed to query aggregate metrics: {}", e.getMessage(), e);
            return Map.of(
                    "totalPnL", 0.0,
                    "winRate", 0.0,
                    "totalTrades", 0L,
                    "winningTrades", 0L,
                    "avgTrade", 0.0,
                    "bestTrade", 0.0,
                    "worstTrade", 0.0,
                    "maxDrawdown", 0.0,
                    "sharpeRatio", 0.0);
        }
    }

    /**
     * Get metrics specifically for Kelly Criterion warm-up
     * Returns winRate, avgWinRatio, avgLossRatio, totalTrades
     */
    public Map<String, Double> getKellyMetrics() {
        if (!enabled) {
            return Map.of(
                    "winRate", 0.0,
                    "avgWinRatio", 0.0,
                    "avgLossRatio", 0.0,
                    "totalTrades", 0.0);
        }

        try {
            // Calculate average winning trade P&L as ratio of position size
            String avgWinFlux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -365d) " +
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> filter(fn: (r) => r._field == \"pnl\") " +
                            "|> filter(fn: (r) => r._value > 0) " +
                            "|> mean()",
                    bucket);

            // Calculate average losing trade P&L as ratio of position size
            String avgLossFlux = String.format(
                    "from(bucket: \"%s\") " +
                            "|> range(start: -365d) " +
                            "|> filter(fn: (r) => r._measurement == \"position_closes\") " +
                            "|> filter(fn: (r) => r._field == \"pnl\") " +
                            "|> filter(fn: (r) => r._value < 0) " +
                            "|> mean()",
                    bucket);

            // Get aggregate metrics for win rate and total trades
            Map<String, Object> aggregates = getAggregateMetrics();
            double winRate = ((Number) aggregates.getOrDefault("winRate", 0.0)).doubleValue() / 100.0; // Convert to 0-1
            long totalTrades = ((Number) aggregates.getOrDefault("totalTrades", 0L)).longValue();

            double avgWin = 0.0;
            double avgLoss = 0.0;

            // Execute average win query
            List<FluxTable> avgWinTables = queryApi.query(avgWinFlux, org);
            if (!avgWinTables.isEmpty() && !avgWinTables.get(0).getRecords().isEmpty()) {
                Object val = avgWinTables.get(0).getRecords().get(0).getValue();
                if (val instanceof Number) {
                    avgWin = ((Number) val).doubleValue();
                }
            }

            // Execute average loss query
            List<FluxTable> avgLossTables = queryApi.query(avgLossFlux, org);
            if (!avgLossTables.isEmpty() && !avgLossTables.get(0).getRecords().isEmpty()) {
                Object val = avgLossTables.get(0).getRecords().get(0).getValue();
                if (val instanceof Number) {
                    avgLoss = ((Number) val).doubleValue();
                }
            }

            // Convert to ratios (assuming average position size of $100)
            // This is a rough approximation - in production, you'd calculate actual ratios
            double avgPositionSize = 100.0; // Default assumption
            double avgWinRatio = avgWin / avgPositionSize;
            double avgLossRatio = avgLoss / avgPositionSize; // Will be negative

            logger.info("📊 Kelly metrics calculated: winRate={:.2f}%, avgWin=${:.2f}, avgLoss=${:.2f}, trades={}",
                    winRate * 100, avgWin, avgLoss, totalTrades);

            return Map.of(
                    "winRate", winRate,
                    "avgWinRatio", avgWinRatio,
                    "avgLossRatio", avgLossRatio,
                    "totalTrades", (double) totalTrades);

        } catch (Exception e) {
            logger.error("Failed to query Kelly metrics: {}", e.getMessage());
            return Map.of(
                    "winRate", 0.0,
                    "avgWinRatio", 0.0,
                    "avgLossRatio", 0.0,
                    "totalTrades", 0.0);
        }
    }

    /**
     * Close connection on shutdown
     */
    public void close() {
        if (client != null) {
            client.close();
            logger.info("InfluxDB connection closed");
        }
    }
}
