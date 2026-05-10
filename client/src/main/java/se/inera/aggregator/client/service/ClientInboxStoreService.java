package se.inera.aggregator.client.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory client inbox storage for demo purposes.
 */
@Service
public class ClientInboxStoreService {

    private final Map<String, List<Map<String, Object>>> messagesByCorrelationId = new ConcurrentHashMap<>();

    public void store(Map<String, Object> payload) {
        String correlationId = extractCorrelationId(payload);
        messagesByCorrelationId
            .computeIfAbsent(correlationId, key -> new CopyOnWriteArrayList<>())
            .add(payload);
    }

    public List<Map<String, Object>> getByCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.trim().isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(messagesByCorrelationId.getOrDefault(correlationId, List.of()));
    }

    private String extractCorrelationId(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Inbox payload is required");
        }
        Object correlationValue = payload.get("correlationId");
        if (correlationValue == null || correlationValue.toString().trim().isEmpty()) {
            throw new IllegalArgumentException("Inbox payload must include correlationId");
        }
        return correlationValue.toString();
    }
}
