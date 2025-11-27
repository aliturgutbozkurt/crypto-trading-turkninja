package com.turkninja.web.socket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        System.out.println("🔌 WebSocket connected: " + session.getId() + " | Total: " + sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        System.out.println("🔌 WebSocket disconnected: " + session.getId() + " | Total: " + sessions.size());
    }

    public void broadcast(String message) {
        int sent = 0;
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(message));
                    sent++;
                } catch (IOException e) {
                    System.err.println("❌ Failed to send to session: " + session.getId());
                }
            }
        }
        // Log occasionally
        if (Math.random() < 0.01) {
            System.out.println("📤 Broadcast to " + sent + " sessions");
        }
    }
}
