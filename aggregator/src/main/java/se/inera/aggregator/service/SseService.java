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
        // Ensure a sink exists and return its Flux for subscribers.
        SinkInfo info = getOrCreateSinkInfo(correlationId);
        return info.sink.asFlux();
    }

    /**
     * Register how many callbacks to expect for this correlationId. When the
     * number of received callbacks reaches expected, the stream will be completed.
     */
    public void registerExpected(String correlationId, int expected) {
        SinkInfo info = getOrCreateSinkInfo(correlationId);
        info.expected = expected;
        // If already received enough (race), complete immediately
        if (info.received.get() >= info.expected) {
            tryEmitCompleteAndRemove(correlationId, info);
        }
    }

    public void sendEvent(String correlationId, JournalCallback callback) {
        SinkInfo info = sinks.get(correlationId);
        if (info == null) return;
        emitNextAndMaybeComplete(correlationId, info, callback);
    }

    /**
     * Complete the SSE stream for a correlationId and include an explicit summary
     * (respondents count). This can be called by business logic to emit accepted-count
     * summaries instead of relying on raw callback count.
     */
    public void completeWithSummary(String correlationId, int respondents) {
        SinkInfo info = sinks.remove(correlationId);
        if (info != null) {
            // Emit a final summary callback so clients can distinguish a graceful close from an error.
            JournalCallback summary = new JournalCallback("AGGREGATOR", null, correlationId, null, "COMPLETE", null, respondents);
            info.sink.tryEmitNext(summary);
            info.sink.tryEmitComplete();
        }
    }

    public void complete(String correlationId) {
        SinkInfo info = sinks.remove(correlationId);
        if (info != null) {
            info.sink.tryEmitComplete();
        }
    }

    private void tryEmitCompleteAndRemove(String correlationId, SinkInfo info) {
        // Emit a final summary callback so clients know this was a deliberate completion
        // and not an error. Use the number of received callbacks as the respondents count.
        int respondents = info.received.get();
        JournalCallback summary = new JournalCallback("AGGREGATOR", null, correlationId, null, "COMPLETE", null, respondents);
        info.sink.tryEmitNext(summary);

    // remove to prevent further events
    sinks.remove(correlationId);

    // complete the sink so subscribers observe onComplete
    info.sink.tryEmitComplete();
    }

    private SinkInfo getOrCreateSinkInfo(String correlationId) {
        return sinks.computeIfAbsent(correlationId, id -> {
            Sinks.Many<JournalCallback> s = Sinks.many().replay().all();
            return new SinkInfo(s);
        });
    }

    private void emitNextAndMaybeComplete(String correlationId, SinkInfo info, JournalCallback callback) {
        info.sink.tryEmitNext(callback);
        int received = info.received.incrementAndGet();
        if (info.expected > 0 && received >= info.expected) {
            tryEmitCompleteAndRemove(correlationId, info);
        }
    }
}
