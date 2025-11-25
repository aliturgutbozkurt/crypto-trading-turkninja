package com.turkninja.web.dto;

import java.time.Instant;

public class SignalDTO {
    private String symbol;
    private String type; // "BUY" or "SELL"
    private String reason;
    private double price;
    private long timestamp;
    private boolean executed; // Was the signal executed or blocked?
    private String status; // "EXECUTED", "BLOCKED_RISK", "BLOCKED_ORDERBOOK", etc.

    public SignalDTO() {
    }

    public SignalDTO(String symbol, String type, String reason, double price, boolean executed, String status) {
        this.symbol = symbol;
        this.type = type;
        this.reason = reason;
        this.price = price;
        this.executed = executed;
        this.status = status;
        this.timestamp = Instant.now().toEpochMilli();
    }

    // Getters and Setters
    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isExecuted() {
        return executed;
    }

    public void setExecuted(boolean executed) {
        this.executed = executed;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
