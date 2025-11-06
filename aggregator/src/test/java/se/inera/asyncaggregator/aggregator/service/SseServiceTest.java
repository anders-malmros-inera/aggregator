package se.inera.asyncaggregator.aggregator.service;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import se.inera.asyncaggregator.aggregator.model.JournalCallback;

public class SseServiceTest {

    @Test
    public void completesWhenExpectedEventsReceived() {
        SseService sse = new SseService();
        String correlationId = "test-correlation-1";

        // Subscribe first
        var flux = sse.subscribe(correlationId);

        // Prepare and send events after subscriber is ready
        StepVerifier.create(flux)
            .then(() -> {
                sse.registerExpected(correlationId, 2);
                sse.sendEvent(correlationId, new JournalCallback("r1", "p1", correlationId, 0, null, null));
                sse.sendEvent(correlationId, new JournalCallback("r2", "p1", correlationId, 0, null, null));
            })
            .expectNextMatches(cb -> cb != null && (cb.getStatus() == null || !"COMPLETED".equals(cb.getStatus())))
            .expectNextMatches(cb -> cb != null && (cb.getStatus() == null || !"COMPLETED".equals(cb.getStatus())))
            .expectComplete()
            .verify();
    }
}
