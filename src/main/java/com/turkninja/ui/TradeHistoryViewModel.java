package com.turkninja.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class TradeHistoryViewModel {
    private final StringProperty symbol;
    private final StringProperty side;
    private final DoubleProperty entryPrice;
    private final DoubleProperty exitPrice;
    private final DoubleProperty pnl;
    private final DoubleProperty roiPercent;
    private final StringProperty openTime;
    private final StringProperty closeTime;
    private final StringProperty duration;
    private final StringProperty closeReason;

    public TradeHistoryViewModel(String symbol, String side, double entryPrice, double exitPrice,
            double pnl, double roiPercent, String openTime, String closeTime,
            String duration, String closeReason) {
        this.symbol = new SimpleStringProperty(symbol);
        this.side = new SimpleStringProperty(side);
        this.entryPrice = new SimpleDoubleProperty(entryPrice);
        this.exitPrice = new SimpleDoubleProperty(exitPrice);
        this.pnl = new SimpleDoubleProperty(pnl);
        this.roiPercent = new SimpleDoubleProperty(roiPercent);
        this.openTime = new SimpleStringProperty(openTime);
        this.closeTime = new SimpleStringProperty(closeTime);
        this.duration = new SimpleStringProperty(duration);
        this.closeReason = new SimpleStringProperty(closeReason);
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

    public double getExitPrice() {
        return exitPrice.get();
    }

    public DoubleProperty exitPriceProperty() {
        return exitPrice;
    }

    public double getPnl() {
        return pnl.get();
    }

    public DoubleProperty pnlProperty() {
        return pnl;
    }

    public double getRoiPercent() {
        return roiPercent.get();
    }

    public DoubleProperty roiPercentProperty() {
        return roiPercent;
    }

    public String getOpenTime() {
        return openTime.get();
    }

    public StringProperty openTimeProperty() {
        return openTime;
    }

    public String getCloseTime() {
        return closeTime.get();
    }

    public StringProperty closeTimeProperty() {
        return closeTime;
    }

    public String getDuration() {
        return duration.get();
    }

    public StringProperty durationProperty() {
        return duration;
    }

    public String getCloseReason() {
        return closeReason.get();
    }

    public StringProperty closeReasonProperty() {
        return closeReason;
    }
}
