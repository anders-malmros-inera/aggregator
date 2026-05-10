package se.inera.aggregator.service;

import org.springframework.stereotype.Service;
import se.inera.aggregator.model.JournalCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory inbox storage for demo purposes.
 */
@Service
public class InboxStoreService {

    private final Map<String, List<JournalCallback>> messagesByCorrelationId = new ConcurrentHashMap<>();

    public void store(JournalCallback callback) {
        if (callback == null || callback.getCorrelationId() == null || callback.getCorrelationId().trim().isEmpty()) {
            throw new IllegalArgumentException("Inbox callback must include correlationId");
        }

        messagesByCorrelationId
            .computeIfAbsent(callback.getCorrelationId(), key -> new CopyOnWriteArrayList<>())
            .add(callback);
    }

    public List<JournalCallback> getByCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.trim().isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(messagesByCorrelationId.getOrDefault(correlationId, List.of()));
    }
}
