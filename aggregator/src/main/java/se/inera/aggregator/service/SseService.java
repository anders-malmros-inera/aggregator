package se.inera.aggregator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import se.inera.aggregator.model.JournalCallback;
import se.inera.aggregator.service.sse.SinkInfo;
import se.inera.aggregator.service.sse.SseEmitter;
import se.inera.aggregator.service.sse.SseSinkManager;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thin orchestration class that delegates sink lifecycle and emission concerns
 * to dedicated collaborators. Keeps methods small and testable.
 */
@Service
public class SseService {

    private static final Logger logger = LoggerFactory.getLogger(SseService.class);

    private final SseSinkManager sinkManager;
    private final SseEmitter emitter;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public SseService() {
        this(new SseSinkManager(), new SseEmitter());
    }

    // package-visible constructor for tests
    SseService(SseSinkManager sinkManager, SseEmitter emitter) {
        this.sinkManager = sinkManager;
        this.emitter = emitter;
    }

    public Flux<JournalCallback> subscribe(String correlationId) {
        SinkInfo info = sinkManager.getOrCreate(correlationId);
        return info.getSink().asFlux()
            .doOnCancel(() -> {
                logger.info("Client disconnected for correlation {}", correlationId);
                cancel(correlationId);
            })
            .doOnTerminate(() -> logger.debug("Stream terminated for correlation {}", correlationId));
    }

    public void registerExpected(String correlationId, int expected) {
        SinkInfo info = sinkManager.registerExpected(correlationId, expected);
        if (info.getReceived() >= info.getExpected() && info.getExpected() > 0) {
            // Race: already have enough responses -- complete with summary
            completeWithSummary(correlationId, info.getRespondents());
        }
    }

    public void scheduleTimeout(String correlationId, long timeoutMs) {
        SinkInfo info = sinkManager.getIfPresent(correlationId);
        if (info == null) return;
        
        var timeoutFuture = scheduler.schedule(() -> {
            logger.warn("Timeout reached for correlation {} after {}ms", correlationId, timeoutMs);
            handleTimeout(correlationId);
        }, timeoutMs, TimeUnit.MILLISECONDS);
        
        info.setTimeoutFuture(timeoutFuture);
    }

    private void handleTimeout(String correlationId) {
        SinkInfo info = sinkManager.getIfPresent(correlationId);
        if (info == null) return;
        
        int received = info.getReceived();
        int expected = info.getExpected();
        
        if (received < expected) {
            logger.info("Timeout: received {}/{} responses for correlation {}", 
                received, expected, correlationId);
            
            // Mark missing resources as timed out
            int missing = expected - received;
            for (int i = 0; i < missing; i++) {
                info.incrementAndGetReceived();
            }
            
            // Complete with partial results
            completeWithSummary(correlationId, info.getRespondents());
        }
    }

    public void sendEvent(String correlationId, JournalCallback callback) {
        SinkInfo info = sinkManager.getIfPresent(correlationId);
        if (info == null) return;
        emitter.emitWithRetries(info.getSink(), callback);
        int received = info.incrementAndGetReceived();
        if (info.getExpected() > 0 && received >= info.getExpected()) {
            completeWithSummary(correlationId, info.getRespondents());
        }
    }

    public void sendEventAndCountRespondent(String correlationId, JournalCallback callback) {
        SinkInfo info = sinkManager.getIfPresent(correlationId);
        if (info == null) return;
        emitter.emitWithRetries(info.getSink(), callback);
        int received = info.incrementAndGetReceived();
        info.incrementAndGetRespondents();
        if (info.getExpected() > 0 && received >= info.getExpected()) {
            completeWithSummary(correlationId, info.getRespondents());
        }
    }

    public void completeWithSummary(String correlationId, int respondents) {
        SinkInfo info = sinkManager.remove(correlationId);
        if (info != null) {
            // Cancel timeout if still pending
            if (info.getTimeoutFuture() != null && !info.getTimeoutFuture().isDone()) {
                info.getTimeoutFuture().cancel(false);
            }
            
            JournalCallback summary = new JournalCallback("AGGREGATOR", null, correlationId, null, "COMPLETE", null, respondents);
            emitter.emitSummaryWithRetries(info.getSink(), summary);
            info.getSink().tryEmitComplete();
        } else {
            logger.debug("completeWithSummary called but no sink found for {}", correlationId);
        }
    }

    public void complete(String correlationId) {
        SinkInfo info = sinkManager.remove(correlationId);
        if (info != null) info.getSink().tryEmitComplete();
    }

    public void cancel(String correlationId) {
        SinkInfo info = sinkManager.remove(correlationId);
        if (info != null) {
            // Cancel timeout if still pending
            if (info.getTimeoutFuture() != null && !info.getTimeoutFuture().isDone()) {
                info.getTimeoutFuture().cancel(false);
            }
            
            // Cancel any pending resource calls
            if (info.getCancellationDisposable() != null && !info.getCancellationDisposable().isDisposed()) {
                logger.info("Cancelling resource calls for correlation {}", correlationId);
                info.getCancellationDisposable().dispose();
            }
            info.getSink().tryEmitComplete();
        }
    }

    public SinkInfo getSinkInfo(String correlationId) {
        return sinkManager.getIfPresent(correlationId);
    }
}
