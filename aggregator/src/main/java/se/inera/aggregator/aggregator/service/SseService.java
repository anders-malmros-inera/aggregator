package se.inera.aggregator.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import se.inera.aggregator.model.JournalCallback;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SseService {
    
    private static class SinkInfo {
        final Sinks.Many<JournalCallback> sink;
        final AtomicInteger received = new AtomicInteger(0);
        volatile int expected = 0;

        SinkInfo(Sinks.Many<JournalCallback> sink) {
            this.sink = sink;
        }
    }

    private final Map<String, SinkInfo> sinks = new ConcurrentHashMap<>();

    public Flux<JournalCallback> subscribe(String correlationId) {
        // Use a replay sink so late subscribers still receive events (and the final COMPLETE)
        SinkInfo info = sinks.computeIfAbsent(correlationId, id -> {
            Sinks.Many<JournalCallback> s = Sinks.many().replay().all();
            return new SinkInfo(s);
        });
        return info.sink.asFlux();
    }

    /**
     * Register how many callbacks to expect for this correlationId. When the
     * number of received callbacks reaches expected, the stream will be completed.
     */
    public void registerExpected(String correlationId, int expected) {
        SinkInfo info = sinks.get(correlationId);
        if (info != null) {
            info.expected = expected;
            // If already received enough (possible race), complete immediately
            if (info.received.get() >= info.expected) {
                tryEmitCompleteAndRemove(correlationId, info);
            }
        } else {
            // Create SinkInfo so that expected is set before subscribe (possible ordering)
            Sinks.Many<JournalCallback> sink = Sinks.many().replay().all();
            SinkInfo newInfo = new SinkInfo(sink);
            newInfo.expected = expected;
            sinks.put(correlationId, newInfo);
        }
    }

    public void sendEvent(String correlationId, JournalCallback callback) {
        SinkInfo info = sinks.get(correlationId);
        if (info != null) {
            info.sink.tryEmitNext(callback);
            int received = info.received.incrementAndGet();
            if (info.expected > 0 && received >= info.expected) {
                tryEmitCompleteAndRemove(correlationId, info);
            }
        }
    }

    public void complete(String correlationId) {
        SinkInfo info = sinks.remove(correlationId);
        if (info != null) {
            info.sink.tryEmitComplete();
        }
    }

    private void tryEmitCompleteAndRemove(String correlationId, SinkInfo info) {
        // remove first to avoid further events
        sinks.remove(correlationId);
        // send a final 'COMPLETE' callback so clients get an explicit final event
        try {
            JournalCallback finalCallback = new JournalCallback(
                "aggregator", null, correlationId, null, "COMPLETE", null, info.received.get());
            info.sink.tryEmitNext(finalCallback);
        } catch (Exception ignored) {
        }
        info.sink.tryEmitComplete();
    }
}
