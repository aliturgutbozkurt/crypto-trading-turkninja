package com.turkninja.web.service;

import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.web.controller.DashboardRestController;
import com.turkninja.web.dto.AccountDTO;
import com.turkninja.web.dto.PositionDTO;
import com.turkninja.web.dto.SignalDTO;
import com.turkninja.web.socket.DashboardWebSocketHandler;
import org.json.JSONObject;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class WebSocketPushService {

    private final FuturesWebSocketService binanceWebSocketService;
    private final DashboardWebSocketHandler dashboardWebSocketHandler;
    private final DashboardRestController restController;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public WebSocketPushService(FuturesWebSocketService binanceWebSocketService,
            DashboardWebSocketHandler dashboardWebSocketHandler,
            @Lazy DashboardRestController restController) {
        this.binanceWebSocketService = binanceWebSocketService;
        this.dashboardWebSocketHandler = dashboardWebSocketHandler;
        this.restController = restController;

        startPushing();
    }

    private void startPushing() {
        // Push updates every 250ms for real-time feel
        // We fetch the latest DTOs from the controller logic which uses the cache
        scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean cacheReady = binanceWebSocketService.isCacheReady();

                // Log every 4 seconds (16 iterations at 250ms)
                if (Math.random() < 0.0625) { // ~1/16 chance
                    System.out.println("📡 WebSocket Push - Cache Ready: " + cacheReady);
                }

                if (cacheReady) {
                    List<PositionDTO> positions = restController.getPositions();
                    AccountDTO account = restController.getAccount();

                    JSONObject update = new JSONObject();
                    update.put("type", "UPDATE");
                    update.put("positions", positions);
                    update.put("account", new JSONObject(account));

                    dashboardWebSocketHandler.broadcast(update.toString());

                    // Log successful pushes occasionally
                    if (Math.random() < 0.01) {
                        System.out.println("✅ Pushed update: " + positions.size() + " positions");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    /**
     * Push trading signal to all connected clients
     */
    public void pushSignal(SignalDTO signal) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "SIGNAL");
            message.put("signal", new JSONObject(signal));

            dashboardWebSocketHandler.broadcast(message.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Push trade close event to all connected clients
     */
    public void pushTradeClose(com.turkninja.web.dto.TradeHistoryDTO trade) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "TRADE_CLOSE");
            message.put("trade", new JSONObject(trade));

            dashboardWebSocketHandler.broadcast(message.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Push metrics update to all connected clients
     */
    public void pushMetrics(java.util.Map<String, Object> metrics) {
        try {
            JSONObject message = new JSONObject();
            message.put("type", "METRICS_UPDATE");
            message.put("metrics", new JSONObject(metrics));

            dashboardWebSocketHandler.broadcast(message.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
