package com.turkninja.infra;

import com.turkninja.config.Config;
import okhttp3.*;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Telegram notification service for real-time trading alerts.
 * Sends notifications for critical events like position changes, stops, etc.
 */
public class TelegramNotifier {
    private static final Logger logger = LoggerFactory.getLogger(TelegramNotifier.class);

    private final String botToken;
    private final String chatId;
    private final boolean enabled;
    private final OkHttpClient client;

    public enum AlertLevel {
        INFO,
        WARNING,
        CRITICAL
    }

    public TelegramNotifier() {
        this.enabled = Boolean.parseBoolean(Config.get("telegram.enabled", "false"));
        this.botToken = Config.get("telegram.bot.token", "");
        this.chatId = Config.get("telegram.chat.id", "");
        this.client = new OkHttpClient();

        if (enabled && !botToken.isEmpty() && !chatId.isEmpty()) {
            logger.info("✅ Telegram notifications enabled (Chat ID: {})", chatId);
        } else if (enabled) {
            logger.warn("⚠️ Telegram enabled but token/chatId missing. Notifications disabled.");
        }
    }

    /**
     * Send an alert message with appropriate emoji based on severity
     */
    public void sendAlert(AlertLevel level, String message) {
        if (!enabled || botToken.isEmpty() || chatId.isEmpty()) {
            return;
        }

        String emoji = switch (level) {
            case CRITICAL -> "🚨";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };

        String formattedMessage = emoji + " " + message;
        sendMessage(formattedMessage);
    }

    /**
     * Send a position opened notification
     */
    public void notifyPositionOpened(String symbol, String side, double entryPrice, double quantity, double sizeUsdt) {
        String message = String.format(
                "📈 *Position Opened*\n" +
                        "Symbol: %s\n" +
                        "Side: %s\n" +
                        "Entry: $%.4f\n" +
                        "Quantity: %.2f\n" +
                        "Size: $%.2f",
                symbol, side, entryPrice, quantity, sizeUsdt);
        sendAlert(AlertLevel.INFO, message);
    }

    /**
     * Send a position closed notification
     */
    public void notifyPositionClosed(String symbol, String side, double exitPrice, double pnl, String reason) {
        String emoji = pnl >= 0 ? "✅" : "❌";
        AlertLevel level = pnl >= 0 ? AlertLevel.INFO : AlertLevel.WARNING;

        String message = String.format(
                "%s *Position Closed*\n" +
                        "Symbol: %s %s\n" +
                        "Exit: $%.4f\n" +
                        "PnL: $%.2f\n" +
                        "Reason: %s",
                emoji, symbol, side, exitPrice, pnl, reason);
        sendAlert(level, message);
    }

    /**
     * Send a trailing stop triggered notification
     */
    public void notifyTrailingStopTriggered(String symbol, String side, double extremePrice, double currentPrice,
            double netProfitPercent) {
        String message = String.format(
                "🎯 *Trailing Stop Triggered*\n" +
                        "Symbol: %s %s\n" +
                        "Extreme: $%.4f\n" +
                        "Current: $%.4f\n" +
                        "Profit Locked: %.2f%%",
                symbol, side, extremePrice, currentPrice, netProfitPercent);
        sendAlert(AlertLevel.INFO, message);
    }

    /**
     * Send high slippage warning
     */
    public void notifyHighSlippage(String symbol, double slippagePercent) {
        String message = String.format(
                "⚠️ *High Slippage Detected*\n" +
                        "Symbol: %s\n" +
                        "Slippage: %.2f%%\n" +
                        "Action: Exiting early",
                symbol, slippagePercent * 100);
        sendAlert(AlertLevel.WARNING, message);
    }

    /**
     * Send circuit breaker activated notification
     */
    public void notifyCircuitBreakerActivated(int consecutiveLosses, long pauseDurationMinutes) {
        String message = String.format(
                "🔴 *Circuit Breaker Activated*\n" +
                        "Consecutive Losses: %d\n" +
                        "Paused For: %d minutes\n" +
                        "Trading halted temporarily",
                consecutiveLosses, pauseDurationMinutes);
        sendAlert(AlertLevel.CRITICAL, message);
    }

    /**
     * Send daily loss limit warning
     */
    public void notifyDailyLossWarning(double dailyLoss, double limit, double percentUsed) {
        String message = String.format(
                "⚠️ *Daily Loss Limit Warning*\n" +
                        "Current Loss: $%.2f\n" +
                        "Limit: $%.2f\n" +
                        "Used: %.0f%%",
                Math.abs(dailyLoss), limit, percentUsed);
        sendAlert(AlertLevel.WARNING, message);
    }

    /**
     * Send partial take profit notification
     */
    public void notifyPartialTakeProfit(String symbol, double percentage, double profitPercent) {
        String message = String.format(
                "💰 *Partial Take Profit*\n" +
                        "Symbol: %s\n" +
                        "Closed: %.0f%%\n" +
                        "At Profit: +%.2f%%",
                symbol, percentage * 100, profitPercent * 100);
        sendAlert(AlertLevel.INFO, message);
    }

    /**
     * Send raw message to Telegram
     */
    private void sendMessage(String text) {
        try {
            String url = String.format("https://api.telegram.org/bot%s/sendMessage", botToken);

            JSONObject json = new JSONObject();
            json.put("chat_id", chatId);
            json.put("text", text);
            json.put("parse_mode", "Markdown");

            RequestBody body = RequestBody.create(
                    json.toString(),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.error("Failed to send Telegram message: {}", response.code());
                }
            }
        } catch (IOException e) {
            logger.error("Error sending Telegram notification", e);
        }
    }

    public boolean isEnabled() {
        return enabled && !botToken.isEmpty() && !chatId.isEmpty();
    }
}
