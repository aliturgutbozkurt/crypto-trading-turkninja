package com.turkninja.web.dto;

import java.time.Instant;

/**
 * DTO for trade history display on dashboard
 * Represents both active and closed positions
 */
public class TradeHistoryDTO {
    private String symbol;
    private String side;
    private double entryPrice;
    private Double exitPrice; // Null for active positions
    private double pnl;
    private double pnlPercent;
    private String exitReason; // Null for active positions
    private Long durationSeconds; // Null for active positions
    private Instant timestamp;
    private String status; // "ACTIVE" or "CLOSED"

    public TradeHistoryDTO() {
    }

    public TradeHistoryDTO(String symbol, String side, double entryPrice, Double exitPrice,
            double pnl, double pnlPercent, String exitReason,
            Long durationSeconds, Instant timestamp, String status) {
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice;
        this.exitPrice = exitPrice;
        this.pnl = pnl;
        this.pnlPercent = pnlPercent;
        this.exitReason = exitReason;
        this.durationSeconds = durationSeconds;
        this.timestamp = timestamp;
        this.status = status;
    }

    // Getters and Setters
    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public double getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public Double getExitPrice() {
        return exitPrice;
    }

    public void setExitPrice(Double exitPrice) {
        this.exitPrice = exitPrice;
    }

    public double getPnl() {
        return pnl;
    }

    public void setPnl(double pnl) {
        this.pnl = pnl;
    }

    public double getPnlPercent() {
        return pnlPercent;
    }

    public void setPnlPercent(double pnlPercent) {
        this.pnlPercent = pnlPercent;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
