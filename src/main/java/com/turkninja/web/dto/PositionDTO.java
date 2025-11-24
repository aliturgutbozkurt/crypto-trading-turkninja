package com.turkninja.web.dto;

public class PositionDTO {
    private String symbol;
    private String side;
    private double entryPrice;
    private double currentPrice;
    private double unrealizedPnL;
    private double roi;
    private double positionSizeUsdt;
    private double balancePercent;

    public PositionDTO() {
    }

    public PositionDTO(String symbol, String side, double entryPrice, double currentPrice,
            double unrealizedPnL, double roi, double positionSizeUsdt, double balancePercent) {
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice;
        this.currentPrice = currentPrice;
        this.unrealizedPnL = unrealizedPnL;
        this.roi = roi;
        this.positionSizeUsdt = positionSizeUsdt;
        this.balancePercent = balancePercent;
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

    public double getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice = currentPrice;
    }

    public double getUnrealizedPnL() {
        return unrealizedPnL;
    }

    public void setUnrealizedPnL(double unrealizedPnL) {
        this.unrealizedPnL = unrealizedPnL;
    }

    public double getRoi() {
        return roi;
    }

    public void setRoi(double roi) {
        this.roi = roi;
    }

    public double getPositionSizeUsdt() {
        return positionSizeUsdt;
    }

    public void setPositionSizeUsdt(double positionSizeUsdt) {
        this.positionSizeUsdt = positionSizeUsdt;
    }

    public double getBalancePercent() {
        return balancePercent;
    }

    public void setBalancePercent(double balancePercent) {
        this.balancePercent = balancePercent;
    }
}
