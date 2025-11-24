package com.turkninja.ui;

import com.turkninja.engine.RiskManager;
import com.turkninja.engine.StrategyEngine;
import com.turkninja.infra.FuturesBinanceService;
import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.infra.SynchronizationService;
import com.turkninja.engine.PositionTracker;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.util.Callback;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);
    private PositionTracker positionTracker;

    @FXML
    private Label totalBalanceLabel;
    @FXML
    private Label pnlLabel;
    @FXML
    private Label dailyPnlLabel;
    @FXML
    private TableView<PositionViewModel> positionsTable;
    @FXML
    private TableColumn<PositionViewModel, String> symbolColumn;
    @FXML
    private TableColumn<PositionViewModel, String> sideColumn;
    @FXML
    private TableColumn<PositionViewModel, Number> positionSizeColumn;
    @FXML
    private TableColumn<PositionViewModel, Number> balancePercentColumn;
    @FXML
    private TableColumn<PositionViewModel, Number> entryPriceColumn;
    @FXML
    private TableColumn<PositionViewModel, Number> currentPriceColumn;
    @FXML
    private TableColumn<PositionViewModel, Number> pnlColumn;
    @FXML
    private TableColumn<PositionViewModel, Number> roiColumn;
    @FXML
    private TableColumn<PositionViewModel, Void> actionColumn;

    @FXML
    private Button emergencyExitButton;
    @FXML
    private Label statusLabel;

    private FuturesBinanceService futuresService;
    private FuturesWebSocketService webSocketService;
    private SynchronizationService syncService;
    private RiskManager riskManager;
    private StrategyEngine strategyEngine;
    private ScheduledExecutorService uiScheduler;
    private final ObservableList<PositionViewModel> positions = FXCollections.observableArrayList();

    // Cache for daily PnL (updated every 30 seconds)
    private volatile double cachedDailyPnL = 0.0;
    private volatile long lastDailyPnLUpdate = 0;

    public void initialize() {
        // Initialize Table Columns
        symbolColumn.setCellValueFactory(cellData -> cellData.getValue().symbolProperty());
        sideColumn.setCellValueFactory(cellData -> cellData.getValue().sideProperty());
        positionSizeColumn.setCellValueFactory(cellData -> cellData.getValue().positionSizeProperty());
        balancePercentColumn.setCellValueFactory(cellData -> cellData.getValue().balancePercentProperty());
        entryPriceColumn.setCellValueFactory(cellData -> cellData.getValue().entryPriceProperty());
        currentPriceColumn.setCellValueFactory(cellData -> cellData.getValue().currentPriceProperty());
        pnlColumn.setCellValueFactory(cellData -> cellData.getValue().pnlProperty());
        roiColumn.setCellValueFactory(cellData -> cellData.getValue().roiPercentProperty());

        // Add Action Button
        addButtonToTable();

        positionsTable.setPlaceholder(new Label("No active positions. Strategy is scanning..."));
        positionsTable.setItems(positions);

        uiScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        uiScheduler.scheduleAtFixedRate(this::updateUI, 0, 3, TimeUnit.SECONDS); // Reduced from 1s to 3s

        // Update daily PnL every 30 seconds in background
        uiScheduler.scheduleAtFixedRate(this::updateDailyPnL, 0, 30, TimeUnit.SECONDS);
    }

    private void addButtonToTable() {
        Callback<TableColumn<PositionViewModel, Void>, TableCell<PositionViewModel, Void>> cellFactory = new Callback<>() {
            @Override
            public TableCell<PositionViewModel, Void> call(final TableColumn<PositionViewModel, Void> param) {
                return new TableCell<>() {
                    private final Button btn = new Button("Exit");

                    {
                        btn.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                        btn.setOnAction((ActionEvent event) -> {
                            PositionViewModel data = getTableView().getItems().get(getIndex());
                            closePosition(data);
                        });
                    }

                    @Override
                    public void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty) {
                            setGraphic(null);
                        } else {
                            setGraphic(btn);
                        }
                    }
                };
            }
        };

        actionColumn.setCellFactory(cellFactory);
    }

    private void closePosition(PositionViewModel position) {
        statusLabel.setText("Closing position: " + position.getSymbol());

        new Thread(() -> {
            try {
                String result = futuresService.closePosition(position.getSymbol());
                // Remove from trackers
                if (positionTracker != null) {
                    positionTracker.removePosition(position.getSymbol(), position.getCurrentPrice());
                }
                if (riskManager != null) {
                    riskManager.clearPosition(position.getSymbol());
                }
                Platform.runLater(() -> {
                    statusLabel.setText("Position closed: " + position.getSymbol());
                    positions.remove(position);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error closing position: " + e.getMessage());
                });
            }
        }).start();
    }

    public void setServices(FuturesBinanceService futuresService, FuturesWebSocketService webSocketService,
            SynchronizationService syncService, RiskManager riskManager, StrategyEngine strategyEngine,
            PositionTracker positionTracker) {
        this.futuresService = futuresService;
        this.webSocketService = webSocketService;
        this.syncService = syncService;
        this.riskManager = riskManager;
        this.strategyEngine = strategyEngine;
        this.positionTracker = positionTracker;

        // Subscribe to position cache updates for real-time UI refresh
        if (this.webSocketService != null) {
            this.webSocketService.addPositionCacheListener(positions -> {
                Platform.runLater(this::updateUI);
            });
        }

        // Start UI update loop (keep this for other updates like balance/PnL)
        startUiScheduler();
    }

    private void startUiScheduler() {
        if (uiScheduler == null || uiScheduler.isShutdown()) {
            uiScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
            uiScheduler.scheduleAtFixedRate(this::updateUI, 0, 1, TimeUnit.SECONDS);

            // Auto-reload positions every 15 seconds
            uiScheduler.scheduleAtFixedRate(() -> {
                Platform.runLater(this::onReloadPositions);
            }, 15, 15, TimeUnit.SECONDS);
        }
    }

    private void updateUI() {
        if (futuresService == null || syncService == null || webSocketService == null)
            return;

        Platform.runLater(() -> {
            try {
                String strategyStatus = (strategyEngine != null && strategyEngine.isTradingActive()) ? "Active"
                        : "Stopped";
                statusLabel.setText("System: Running | Strategy: " + strategyStatus);

                // Get cached account data from WebSocket (NO REST API CALL)
                org.json.JSONObject account = webSocketService.getCachedAccountInfo();

                logger.debug("updateUI called - account is null: {}", (account == null));
                if (account != null) {
                    logger.debug("Account keys: {}", account.keySet());
                    logger.debug("totalWalletBalance: {}", account.optDouble("totalWalletBalance", -1));
                    logger.debug("totalMarginBalance: {}", account.optDouble("totalMarginBalance", -1));
                }

                if (account != null) {
                    // Update balance (Use Margin Balance to show Equity = Wallet + Unrealized PnL)
                    if (account.has("totalMarginBalance")) {
                        double balance = account.getDouble("totalMarginBalance");
                        if (totalBalanceLabel != null) {
                            totalBalanceLabel.setText(String.format("Balance: $%.2f", balance));
                            logger.debug("Set balance to: ${}", balance);
                        } else {
                            logger.error("totalBalanceLabel is NULL!");
                        }
                    } else if (account.has("totalWalletBalance")) {
                        double wallet = account.getDouble("totalWalletBalance");
                        if (totalBalanceLabel != null) {
                            totalBalanceLabel.setText(String.format("Balance: $%.2f", wallet));
                            logger.debug("Set balance (wallet) to: ${}", wallet);
                        } else {
                            logger.error("totalBalanceLabel is NULL!");
                        }
                    }

                    // Update unrealized PnL (if available)
                    if (account.has("totalUnrealizedProfit")) {
                        double unrealizedPnl = account.getDouble("totalUnrealizedProfit");
                        if (pnlLabel != null) {
                            pnlLabel.setText(String.format("PnL: $%.2f", unrealizedPnl));
                            logger.debug("Set PnL to: ${}", unrealizedPnl);
                        } else {
                            logger.error("pnlLabel is NULL!");
                        }
                    }
                } else {
                    // Cache not ready yet
                    statusLabel.setText("System: Connecting to WebSocket...");
                }

                // Get cached positions from WebSocket (NO REST API CALL)
                org.json.JSONArray positionsArray = webSocketService.getCachedPositions();
                // logger.debug("Cached positions array size: {}", positionsArray != null ?
                // positionsArray.length() : 0);

                // Check if cache is ready
                if (positionsArray == null) {
                    logger.debug("No position cache yet – keeping existing UI rows");
                    // Don't clear positions if cache is null, to avoid flickering or empty table on
                    // startup
                    // But if we have never loaded positions, we might want to clear?
                    // Actually, if it's null, it means we haven't received data yet.
                    // If we have data in UI, we should keep it.
                    return;
                }

                // Get balance for percentage calculations
                double balance = account != null ? account.optDouble("totalMarginBalance", 0.0) : 0.0;

                // Display cached daily PnL (updated in background)
                if (dailyPnlLabel != null) {
                    dailyPnlLabel.setText(String.format("Daily: $%.2f", cachedDailyPnL));

                    // Color based on profit/loss
                    if (cachedDailyPnL > 0) {
                        dailyPnlLabel.setStyle("-fx-text-fill: #2ecc71;");
                    } else if (cachedDailyPnL < 0) {
                        dailyPnlLabel.setStyle("-fx-text-fill: #e74c3c;");
                    } else {
                        dailyPnlLabel.setStyle("-fx-text-fill: #f39c12;");
                    }
                }

                // Build NEW positions list (don't clear existing until we're done)
                ObservableList<PositionViewModel> newPositions = FXCollections.observableArrayList();
                double totalUnrealizedPnL = 0.0; // Calculate total PnL from all positions

                if (positionsArray != null) {
                    // Add active positions
                    for (int i = 0; i < positionsArray.length(); i++) {
                        org.json.JSONObject pos = positionsArray.getJSONObject(i);
                        double positionAmt = pos.getDouble("positionAmt");

                        if (positionAmt != 0) { // Only add active positions
                            String symbol = pos.getString("symbol");
                            String side = positionAmt > 0 ? "LONG" : "SHORT";
                            double entryPrice = pos.getDouble("entryPrice");

                            // Get current price from mark price (REAL-TIME)
                            double currentPrice = futuresService.getSymbolPriceTicker(symbol);

                            // Calculate unrealized PnL in real-time
                            double unrealizedProfit = (currentPrice - entryPrice) * positionAmt;

                            // Ignore dust positions (< $5 value)
                            double notionalValue = Math.abs(positionAmt * currentPrice);
                            if (notionalValue < 5.0) {
                                continue;
                            }

                            totalUnrealizedPnL += unrealizedProfit;

                            // Calculate position size in USDT (notional value)
                            double positionSizeUsdt = notionalValue;

                            // Calculate % of balance (margin used / balance * 100)
                            // With 20x leverage: margin used = notional / 20
                            double marginUsed = notionalValue / 20.0;
                            double balancePercent = (balance > 0) ? (marginUsed / balance) * 100.0 : 0.0;

                            // Calculate ROI % (PnL / margin * 100)
                            // This shows the leverage-adjusted return
                            double roiPercent = (marginUsed > 0) ? (unrealizedProfit / marginUsed) * 100.0 : 0.0;

                            logger.debug("Adding position to UI: {} side={} entry={} current={} pnl={} ROI={}%", symbol,
                                    side,
                                    entryPrice, currentPrice, unrealizedProfit, roiPercent);
                            newPositions.add(
                                    new PositionViewModel(symbol, side, entryPrice, currentPrice, unrealizedProfit,
                                            roiPercent, positionSizeUsdt, balancePercent));
                        }
                    }

                    // Only NOW update the UI (atomic swap - no flickering)
                    positions.clear();
                    positions.addAll(newPositions);

                    // Update total PnL label with calculated value
                    if (pnlLabel != null) {
                        pnlLabel.setText(String.format("PnL: $%.2f", totalUnrealizedPnL));
                    }

                    // Update balance: wallet balance + total unrealized PnL
                    if (account != null && totalBalanceLabel != null) {
                        double walletBalance = account.optDouble("totalWalletBalance", 0.0);
                        double totalBalance = walletBalance + totalUnrealizedPnL;
                        totalBalanceLabel.setText(String.format("Balance: $%.2f", totalBalance));
                    }
                }

            } catch (Exception e) {
                statusLabel.setText("Error: " + e.getMessage());
                System.err.println("ERROR in updateUI: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @FXML
    private void onEmergencyExit() {
        statusLabel.setText("EMERGENCY EXIT TRIGGERED!");

        new Thread(() -> {
            try {
                if (riskManager != null) {
                    riskManager.emergencyExit();
                }
                Platform.runLater(() -> {
                    positions.clear();
                    statusLabel.setText("All positions closed");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error during emergency exit: " + e.getMessage());
                });
            }
        }).start();
    }

    /**
     * Update daily PnL in background (runs every 30 seconds)
     */
    private void updateDailyPnL() {
        try {
            long startOfDay = getStartOfDayTimestamp();
            String incomeHistory = futuresService.getIncomeHistory(null, "REALIZED_PNL", startOfDay, null, 1000);
            org.json.JSONArray incomeArray = new org.json.JSONArray(incomeHistory);

            double dailyPnL = 0.0;
            for (int i = 0; i < incomeArray.length(); i++) {
                org.json.JSONObject income = incomeArray.getJSONObject(i);
                dailyPnL += income.optDouble("income", 0.0);
            }

            cachedDailyPnL = dailyPnL;
            lastDailyPnLUpdate = System.currentTimeMillis();
            logger.debug("Updated daily PnL: ${}", dailyPnL);
        } catch (Exception e) {
            logger.error("Failed to update daily PnL: {}", e.getMessage());
        }
    }

    /**
     * Get the start of today (00:00:00) in milliseconds
     */
    private long getStartOfDayTimestamp() {
        java.time.LocalDate today = java.time.LocalDate.now();
        return today.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @FXML
    private void onReloadPositions() {
        logger.info("🔄 Reloading positions triggered (manual or auto-refresh)");
        statusLabel.setText("Reloading positions from Binance...");

        new Thread(() -> {
            try {
                // Reload positions from Binance
                String positionsJson = futuresService.getPositionInfo();
                org.json.JSONArray positionsArr = new org.json.JSONArray(positionsJson);

                // Clear ALL internal tracking first
                if (positionTracker != null) {
                    // Get all tracked symbols and clear them
                    var trackedPositions = positionTracker.getAllPositions();
                    for (String symbol : trackedPositions.keySet()) {
                        positionTracker.removePosition(symbol, 0.0);
                        logger.info("Cleared tracked position: {}", symbol);
                    }
                }

                // Update WebSocket cache
                webSocketService.setCachedPositions(positionsArr);

                // Re-sync PositionTracker with fresh data (dust filtering applied)
                if (positionTracker != null) {
                    positionTracker.syncPositions(positionsArr);
                }

                Platform.runLater(() -> {
                    positions.clear(); // Clear UI
                    statusLabel.setText("✅ Positions reloaded - dust filtered");
                    logger.info("Positions fully reloaded and re-synced from Binance");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error reloading: " + e.getMessage());
                    logger.error("Failed to reload positions", e);
                });
            }
        }).start();
    }

    @FXML
    private void onViewHistory() {
        new Thread(() -> {
            try {
                statusLabel.setText("Loading trade history...");

                // Query MongoDB for last 50 closed trades
                var tradeRepository = positionTracker != null ? positionTracker.getTradeRepository() : null;
                if (tradeRepository == null) {
                    Platform.runLater(() -> statusLabel.setText("Trade repository not available"));
                    return;
                }

                var closedTrades = tradeRepository.findRecentClosedTrades(50);

                Platform.runLater(() -> {
                    showTradeHistoryPopup(closedTrades);
                    statusLabel.setText("Trade history loaded");
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error loading history: " + e.getMessage());
                    logger.error("Failed to load trade history", e);
                });
            }
        }).start();
    }

    private void showTradeHistoryPopup(java.util.List<com.turkninja.model.Trade> trades) {
        javafx.stage.Stage popup = new javafx.stage.Stage();
        popup.setTitle("Trade History - Last " + trades.size() + " Trades");

        // Create table
        javafx.scene.control.TableView<TradeHistoryViewModel> table = new javafx.scene.control.TableView<>();
        javafx.collections.ObservableList<TradeHistoryViewModel> items = javafx.collections.FXCollections
                .observableArrayList();

        // Define columns
        javafx.scene.control.TableColumn<TradeHistoryViewModel, String> symCol = new javafx.scene.control.TableColumn<>(
                "Symbol");
        symCol.setCellValueFactory(c -> c.getValue().symbolProperty());
        symCol.setPrefWidth(100);

        javafx.scene.control.TableColumn<TradeHistoryViewModel, String> sideCol = new javafx.scene.control.TableColumn<>(
                "Side");
        sideCol.setCellValueFactory(c -> c.getValue().sideProperty());
        sideCol.setPrefWidth(80);

        javafx.scene.control.TableColumn<TradeHistoryViewModel, Number> entryCol = new javafx.scene.control.TableColumn<>(
                "Entry");
        entryCol.setCellValueFactory(c -> c.getValue().entryPriceProperty());
        entryCol.setPrefWidth(100);

        javafx.scene.control.TableColumn<TradeHistoryViewModel, Number> exitCol = new javafx.scene.control.TableColumn<>(
                "Exit");
        exitCol.setCellValueFactory(c -> c.getValue().exitPriceProperty());
        exitCol.setPrefWidth(100);

        javafx.scene.control.TableColumn<TradeHistoryViewModel, Number> pnlCol = new javafx.scene.control.TableColumn<>(
                "PnL ($)");
        pnlCol.setCellValueFactory(c -> c.getValue().pnlProperty());
        pnlCol.setPrefWidth(100);

        javafx.scene.control.TableColumn<TradeHistoryViewModel, Number> roiCol = new javafx.scene.control.TableColumn<>(
                "ROI %");
        roiCol.setCellValueFactory(c -> c.getValue().roiPercentProperty());
        roiCol.setPrefWidth(100);

        javafx.scene.control.TableColumn<TradeHistoryViewModel, String> closeReasonCol = new javafx.scene.control.TableColumn<>(
                "Close Reason");
        closeReasonCol.setCellValueFactory(c -> c.getValue().closeReasonProperty());
        closeReasonCol.setPrefWidth(150);

        javafx.scene.control.TableColumn<TradeHistoryViewModel, String> durationCol = new javafx.scene.control.TableColumn<>(
                "Duration");
        durationCol.setCellValueFactory(c -> c.getValue().durationProperty());
        durationCol.setPrefWidth(100);

        table.getColumns().addAll(symCol, sideCol, entryCol, exitCol, pnlCol, roiCol, closeReasonCol, durationCol);

        // Populate data
        for (com.turkninja.model.Trade trade : trades) {
            String symbol = trade.getSymbol();
            String side = trade.getSide();
            double entry = trade.getEntryPrice();
            double exit = trade.getExitPrice();
            double pnl = trade.getPnl();
            double notional = Math.abs(trade.getQuantity() * entry);
            double margin = notional / 20.0; // 20x leverage
            double roi = (margin > 0) ? (pnl / margin) * 100.0 : 0.0;

            String openTime = new java.text.SimpleDateFormat("HH:mm:ss").format(trade.getTimestamp());
            String closeTime = trade.getCloseTime() != null
                    ? new java.text.SimpleDateFormat("HH:mm:ss").format(trade.getCloseTime())
                    : "-";

            long durationMs = trade.getCloseTime() != null
                    ? trade.getCloseTime().getTime() - trade.getTimestamp().getTime()
                    : 0;
            long durationMin = durationMs / 60000;
            String duration = durationMin + "m";

            String closeReason = trade.getStatus() != null ? trade.getStatus() : "UNKNOWN";

            items.add(new TradeHistoryViewModel(symbol, side, entry, exit, pnl, roi,
                    openTime, closeTime, duration, closeReason));
        }

        table.setItems(items);

        javafx.scene.Scene scene = new javafx.scene.Scene(table, 950, 600);
        popup.setScene(scene);
        popup.show();
    }

    public void shutdown() {
        if (uiScheduler != null)
            uiScheduler.shutdownNow();
    }

    public void updateStatus(String message) {
        Platform.runLater(() -> statusLabel.setText(message));
    }
}
