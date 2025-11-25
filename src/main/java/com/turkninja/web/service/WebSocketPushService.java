package com.turkninja.web.service;

import com.turkninja.infra.FuturesWebSocketService;
import com.turkninja.web.controller.DashboardRestController;
import com.turkninja.web.dto.AccountDTO;
import com.turkninja.web.dto.PositionDTO;
import com.turkninja.web.dto.SignalDTO;
import com.turkninja.web.socket.DashboardWebSocketHandler;
import org.json.JSONObject;
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
            DashboardRestController restController) {
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
                if (binanceWebSocketService.isCacheReady()) {
                    List<PositionDTO> positions = restController.getPositions();
                    AccountDTO account = restController.getAccount();

                    JSONObject update = new JSONObject();
                    update.put("type", "UPDATE");
                    update.put("positions", positions);
                    update.put("account", new JSONObject(account));

                    dashboardWebSocketHandler.broadcast(update.toString());
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
}
