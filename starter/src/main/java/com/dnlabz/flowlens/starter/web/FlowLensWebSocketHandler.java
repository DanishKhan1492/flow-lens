package com.dnlabz.flowlens.starter.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dnlabz.flowlens.starter.model.TraceRecord;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Logger;

/**
 * WebSocket handler that pushes trace events to all connected dashboard clients.
 *
 * <p>Messages sent:
 * <ul>
 *   <li>{@code {"type":"trace","record":{...}}} — new trace completed</li>
 *   <li>{@code {"type":"clear"}} — all traces deleted</li>
 *   <li>{@code {"type":"pong"}} — response to client ping heartbeats</li>
 * </ul>
 */
public class FlowLensWebSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = Logger.getLogger(FlowLensWebSocketHandler.class.getName());

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper;

    public FlowLensWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        LOG.fine("[FlowLens] WS client connected: " + session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Respond to heartbeat pings from the dashboard
        if (message.getPayload().contains("\"ping\"")) {
            try {
                session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
            } catch (IOException ignored) { /* best-effort */ }
        }
    }

    /** Serialises and broadcasts a completed {@link TraceRecord} to all clients. */
    public void broadcastTrace(TraceRecord record) {
        try {
            String json = "{\"type\":\"trace\",\"record\":" + objectMapper.writeValueAsString(record) + "}";
            broadcast(json);
        } catch (JsonProcessingException e) {
            LOG.warning("[FlowLens] Failed to serialize trace record: " + e.getMessage());
        }
    }

    /** Broadcasts a clear event so clients empty their in-memory trace list. */
    public void broadcastClear() {
        broadcast("{\"type\":\"clear\"}");
    }

    private void broadcast(String json) {
        TextMessage msg = new TextMessage(json);
        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) s.sendMessage(msg);
            } catch (IOException e) {
                LOG.fine("[FlowLens] Failed to send to WS client " + s.getId() + ": " + e.getMessage());
            }
        }
    }
}
