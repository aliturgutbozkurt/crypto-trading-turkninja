package com.turkninja.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class PositionViewModel {
    private final StringProperty symbol;
    private final StringProperty side;
    private final DoubleProperty entryPrice;
    private final DoubleProperty currentPrice;
    private final DoubleProperty pnl;
    private final DoubleProperty roiPercent; // ROI % based on margin (with 20x leverage)
    private final DoubleProperty positionSize;
    private final DoubleProperty balancePercent;

    public PositionViewModel(String symbol, String side, double entryPrice, double currentPrice, double pnl,
            double roiPercent, double positionSize, double balancePercent) {
        this.symbol = new SimpleStringProperty(symbol);
        this.side = new SimpleStringProperty(side);
        this.entryPrice = new SimpleDoubleProperty(entryPrice);
        this.currentPrice = new SimpleDoubleProperty(currentPrice);
        this.pnl = new SimpleDoubleProperty(pnl);
        this.roiPercent = new SimpleDoubleProperty(roiPercent);
        this.positionSize = new SimpleDoubleProperty(positionSize);
        this.balancePercent = new SimpleDoubleProperty(balancePercent);
    }

    public String getSymbol() {
        return symbol.get();
    }

    public StringProperty symbolProperty() {
        return symbol;
    }

    public String getSide() {
        return side.get();
    }

    public StringProperty sideProperty() {
        return side;
    }

    public double getEntryPrice() {
        return entryPrice.get();
    }

    public DoubleProperty entryPriceProperty() {
        return entryPrice;
    }

    public double getCurrentPrice() {
        return currentPrice.get();
    }

    public DoubleProperty currentPriceProperty() {
        return currentPrice;
    }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice.set(currentPrice);
    }

    public double getPnl() {
        return pnl.get();
    }

    public DoubleProperty pnlProperty() {
        return pnl;
    }

    public void setPnl(double pnl) {
        this.pnl.set(pnl);
    }

    public double getRoiPercent() {
        return roiPercent.get();
    }

    public DoubleProperty roiPercentProperty() {
        return roiPercent;
    }

    public double getPositionSize() {
        return positionSize.get();
    }

    public DoubleProperty positionSizeProperty() {
        return positionSize;
    }

    public double getBalancePercent() {
        return balancePercent.get();
    }

    public DoubleProperty balancePercentProperty() {
        return balancePercent;
    }
}
