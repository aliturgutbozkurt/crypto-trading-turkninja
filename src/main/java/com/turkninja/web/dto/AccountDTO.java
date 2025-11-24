package com.turkninja.web.dto;

public class AccountDTO {
    private double totalBalance;
    private double unrealizedPnL;
    private double dailyPnL;
    private int activePositions;
    private String strategyStatus;

    public AccountDTO() {
    }

    public AccountDTO(double totalBalance, double unrealizedPnL, double dailyPnL,
            int activePositions, String strategyStatus) {
        this.totalBalance = totalBalance;
        this.unrealizedPnL = unrealizedPnL;
        this.dailyPnL = dailyPnL;
        this.activePositions = activePositions;
        this.strategyStatus = strategyStatus;
    }

    // Getters and Setters
    public double getTotalBalance() {
        return totalBalance;
    }

    public void setTotalBalance(double totalBalance) {
        this.totalBalance = totalBalance;
    }

    public double getUnrealizedPnL() {
        return unrealizedPnL;
    }

    public void setUnrealizedPnL(double unrealizedPnL) {
        this.unrealizedPnL = unrealizedPnL;
    }

    public double getDailyPnL() {
        return dailyPnL;
    }

    public void setDailyPnL(double dailyPnL) {
        this.dailyPnL = dailyPnL;
    }

    public int getActivePositions() {
        return activePositions;
    }

    public void setActivePositions(int activePositions) {
        this.activePositions = activePositions;
    }

    public String getStrategyStatus() {
        return strategyStatus;
    }

    public void setStrategyStatus(String strategyStatus) {
        this.strategyStatus = strategyStatus;
    }
}
