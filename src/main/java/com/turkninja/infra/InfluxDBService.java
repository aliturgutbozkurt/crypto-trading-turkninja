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
            double positionSizeUsdt, Instant timestamp) {
        if (!enabled)
            return;

        try {
            Point point = Point.measurement("trades")
                    .addTag("symbol", symbol)
                    .addTag("side", side)
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
     * Write position close event
     */
    public void writePositionClose(String symbol, String side, double exitPrice,
            double pnl, String reason, long durationSeconds,
            Instant timestamp) {
        if (!enabled)
            return;

        try {
            Point point = Point.measurement("position_closes")
                    .addTag("symbol", symbol)
                    .addTag("side", side)
                    .addTag("reason", reason)
                    .addField("exit_price", exitPrice)
                    .addField("pnl", pnl)
                    .addField("duration_seconds", durationSeconds)
                    .addField("win", pnl > 0 ? 1 : 0)
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
     * Close connection on shutdown
     */
    public void close() {
        if (client != null) {
            client.close();
            logger.info("InfluxDB connection closed");
        }
    }
}
