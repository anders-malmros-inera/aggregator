package se.inera.aggregator.service.sse;

import reactor.core.Disposable;
import reactor.core.publisher.Sinks;
import se.inera.aggregator.model.JournalCallback;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lightweight holder for a sink and its expected/received state.
 */
public class SinkInfo {
    private final Sinks.Many<JournalCallback> sink;
    private final AtomicInteger received = new AtomicInteger(0);
    private final AtomicInteger respondents = new AtomicInteger(0);
    private volatile int expected = 0;
    private volatile Disposable cancellationDisposable;
    private volatile ScheduledFuture<?> timeoutFuture;

    public SinkInfo(Sinks.Many<JournalCallback> sink) {
        this.sink = sink;
    }

    public Sinks.Many<JournalCallback> getSink() {
        return sink;
    }

    public int incrementAndGetReceived() {
        return received.incrementAndGet();
    }

    public int getReceived() {
        return received.get();
    }

    public int incrementAndGetRespondents() {
        return respondents.incrementAndGet();
    }

    public int getRespondents() {
        return respondents.get();
    }

    public int getExpected() {
        return expected;
    }

    public void setExpected(int expected) {
        this.expected = expected;
    }

    public void setCancellationDisposable(Disposable disposable) {
        this.cancellationDisposable = disposable;
    }

    public Disposable getCancellationDisposable() {
        return cancellationDisposable;
    }

    public void setTimeoutFuture(ScheduledFuture<?> future) {
        this.timeoutFuture = future;
    }

    public ScheduledFuture<?> getTimeoutFuture() {
        return timeoutFuture;
    }
}
