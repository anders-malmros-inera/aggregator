# Architecture Decision Records

## Overview

This document captures key architectural decisions made during the development of the aggregator system, including the rationale, alternatives considered, and consequences.

---

## ADR-001: Use Server-Sent Events (SSE) for Real-Time Updates

**Status**: Accepted

**Context**:
The system needs to stream aggregated results to clients as resource callbacks arrive, providing real-time feedback during potentially long-running operations.

**Decision**:
Implement SSE using Spring WebFlux's `Sinks.Many<ServerSentEvent<?>>` for unidirectional server-to-client streaming.

**Alternatives Considered**:

1. **WebSockets**
   - Pros: Bidirectional, full-duplex communication
   - Cons: Overkill for one-way updates, more complex, potential firewall issues

2. **Long Polling**
   - Pros: Universal browser support, simple fallback
   - Cons: High overhead, inefficient, artificial delays

3. **Short Polling**
   - Pros: Simplest to implement
   - Cons: Very inefficient, high latency, server load

**Rationale**:
- SSE perfectly fits the use case: server pushes updates to client
- HTTP-based, no special protocol requirements
- Built-in browser reconnection support
- Lower overhead than WebSockets
- Native browser API (`EventSource`)
- Spring WebFlux has excellent SSE support

**Consequences**:
- ✅ Real-time updates with minimal overhead
- ✅ Simple client implementation
- ✅ Automatic reconnection handling
- ✅ Firewall-friendly (HTTP)
- ⚠️ Unidirectional only (acceptable for this use case)
- ⚠️ Text-based (JSON serialization required)

---

## ADR-002: Separate Counting for Respondents vs. Errors

**Status**: Accepted

**Context**:
Resources can reject requests (401 Unauthorized), timeout, experience connection errors, or succeed. The system needs clear metrics distinguishing successful responses from failures.

**Decision**:
Implement triple counter system in `SinkInfo`:
- `received`: All responses (accepted + rejected + errors + timeouts)
- `respondents`: Only successful callbacks with journal notes
- `errors`: TIMEOUT, CONNECTION_CLOSED, ERROR (excludes REJECTED/401)

**Alternatives Considered**:

1. **Single Counter**
   - Pros: Simplest implementation
   - Cons: No distinction between success/failure/rejection

2. **Dual Counter (received/respondents)**
   - Pros: Simpler than triple
   - Cons: Can't distinguish errors from legitimate rejections

**Rationale**:
- 401 Unauthorized is a legitimate business response, not an error
- Clients need to know: how many succeeded vs. how many failed unexpectedly
- REJECTED should not inflate error counts
- Timeouts and connection issues are true errors

**Consequences**:
- ✅ Clear separation of business rejections vs. technical errors
- ✅ Accurate error reporting in summary messages
- ✅ Better observability for monitoring
- ⚠️ Slightly more complex state management
- ⚠️ Requires atomic operations (AtomicInteger)

---

## ADR-003: Timeout Monitors Callback Waiting, Not HTTP Calls

**Status**: Accepted (Fixed from initial implementation)

**Context**:
Initial implementation applied timeout to initial HTTP POST calls, which returned immediately (200 OK or 401). The actual waiting happens during callback collection, not during HTTP requests.

**Decision**:
Schedule timeout task via `ScheduledExecutorService` **after** resources accept requests, monitoring the entire callback waiting period.

**Previous Approach** (Rejected):
```java
webClient.post()
    .timeout(Duration.ofMillis(timeoutMs))  // ❌ Wrong: times out HTTP call
    .retrieve()
```

**Current Approach** (Accepted):
```java
// 1. Make parallel HTTP calls (fast, ~milliseconds)
Flux.merge(resourceCalls).collectList().block();

// 2. Schedule timeout for callback waiting period (slow, ~seconds)
ScheduledFuture<?> timeoutFuture = scheduler.schedule(
    () -> handleTimeout(correlationId),
    timeoutMs,
    TimeUnit.MILLISECONDS
);
```

**Rationale**:
- HTTP calls complete in milliseconds (just acceptance/rejection)
- Actual processing takes seconds (delay simulation or real work)
- Timeout should monitor callback collection, not HTTP requests
- Client expects timeout to apply to entire operation, not just dispatch

**Consequences**:
- ✅ Timeout actually works as intended
- ✅ Partial results when some resources timeout
- ✅ Proper cleanup when timeout expires
- ⚠️ Requires scheduled task cancellation on completion
- ⚠️ More complex lifecycle management

---

## ADR-004: Support Two Aggregation Strategies

**Status**: Accepted

**Context**:
Different use cases require different trade-offs between real-time feedback and simplicity. Some clients need immediate complete results; others benefit from progressive updates.

**Decision**:
Implement two strategies selectable via request parameter:

1. **SSE (Server-Sent Events)** - Default
   - Asynchronous callbacks
   - Real-time streaming
   - Immediate correlationId response
   - Progressive UI updates

2. **Wait-for-Everyone (Synchronous)**
   - Direct resource calls
   - Single complete response
   - No SSE connection
   - Batch processing friendly

**Alternatives Considered**:

1. **SSE Only**
   - Pros: Simplest codebase
   - Cons: Overkill for simple clients, requires SSE handling

2. **Synchronous Only**
   - Pros: Simplest protocol
   - Cons: No real-time feedback, blocking waits

**Rationale**:
- Different clients have different needs
- SSE excellent for interactive UIs (real-time feedback)
- Synchronous better for API integrations, batch jobs
- Strategy pattern allows clean separation
- Shared infrastructure (resource services support both)

**Consequences**:
- ✅ Flexibility for different use cases
- ✅ Better API integration story
- ✅ Still defaults to modern SSE approach
- ⚠️ Two code paths to maintain
- ⚠️ Resources must support both endpoints (`/journals` and `/journals/direct`)

---

## ADR-005: Client Disconnect Triggers Comprehensive Cleanup

**Status**: Accepted

**Context**:
Users may close browser tabs, navigate away, or lose connections. Orphaned resources should not continue processing, and timeout tasks should not fire unnecessarily.

**Decision**:
Implement cleanup via Reactor's `doOnCancel()`:

```java
flux.doOnCancel(() -> {
    cancellationDisposable.dispose();      // Cancel pending resource calls
    timeoutFuture.cancel(false);           // Cancel timeout task
    sinkManager.removeSink(correlationId);  // Remove sink
});
```

**Alternatives Considered**:

1. **No Cleanup**
   - Pros: Simpler code
   - Cons: Resource waste, late callbacks, memory leaks

2. **Timeout-Based Cleanup**
   - Pros: Automatic after timeout
   - Cons: Wastes resources during timeout period

3. **Manual Close Endpoint**
   - Pros: Explicit control
   - Cons: Requires client cooperation, not automatic

**Rationale**:
- Browser disconnect is immediate and detectable
- Continuing to process after disconnect wastes resources
- Timeout tasks firing after disconnect causes unnecessary work
- Late callbacks to removed sinks should be safely ignored

**Consequences**:
- ✅ Immediate resource reclamation
- ✅ No orphaned timeout tasks
- ✅ Graceful handling of disconnects
- ✅ Memory-efficient
- ⚠️ Requires proper disposable management
- ⚠️ Must handle race conditions (late callbacks)

---

## ADR-006: Demo Uses Fixed Resources with Client-Controlled Delays

**Status**: Accepted (Demo Pattern)

**Context**:
The demo needs to showcase aggregation patterns, timeout behavior, error handling, and rejection scenarios in a predictable, reproducible way.

**Decision**:
Implement demo-specific features:
- **Fixed 3 resources** (resource-1, resource-2, resource-3)
- **Client-provided delays** parameter controlling resource behavior
- **Magic number -1** to trigger 401 Unauthorized rejections
- **Clear documentation** distinguishing demo from production

**Production Pattern** (Future):
```java
// 1. Query Information Location Index (ILI)
List<String> resourceUrls = iliService.findResourcesForPatient(patientId);

// 2. Dynamic aggregation
for (String url : resourceUrls) {
    callResource(url, patientId);
}
```

**Rationale**:
- Demo needs predictability for testing and showcasing
- Fixed resources simplify Docker Compose setup
- Client-controlled delays enable scenario testing:
  - All succeed: `1000,2000,3000`
  - One rejects: `-1,1000,2000`
  - Timeout scenario: `1000,2000,15000` with 10s timeout
- Clear separation prevents confusion about production usage

**Consequences**:
- ✅ Predictable demo behavior
- ✅ Easy scenario testing
- ✅ Simple Docker setup
- ✅ Clear documentation of limitations
- ⚠️ Must not be used in production
- ⚠️ Requires ILI integration for production
- ⚠️ Documentation must emphasize demo nature

---

## ADR-007: Use Project Reactor for Reactive Streams

**Status**: Accepted

**Context**:
The system requires non-blocking I/O, parallel resource calls, SSE streaming, and efficient resource utilization under load.

**Decision**:
Use Spring WebFlux with Project Reactor (Mono, Flux, Sinks) for reactive programming model.

**Alternatives Considered**:

1. **Traditional Spring MVC (Blocking)**
   - Pros: Simpler mental model, familiar
   - Cons: Thread-per-request, poor scalability, no SSE support

2. **RxJava**
   - Pros: Mature reactive library
   - Cons: Not Spring's standard, less integration

3. **Kotlin Coroutines**
   - Pros: Simpler syntax
   - Cons: Requires Kotlin, less Java ecosystem support

**Rationale**:
- Spring WebFlux native integration
- Excellent SSE support via `Sinks.Many`
- Non-blocking I/O for parallel resource calls
- Efficient resource utilization (event loop model)
- Built-in backpressure handling
- Standard in Spring ecosystem

**Consequences**:
- ✅ Highly scalable architecture
- ✅ Native SSE support
- ✅ Efficient parallel operations
- ✅ Built-in backpressure
- ⚠️ Steeper learning curve
- ⚠️ More complex debugging
- ⚠️ Requires reactive mindset

---

## ADR-008: Use Scheduled Executor for Timeout Tasks

**Status**: Accepted

**Context**:
The system needs to schedule future timeout tasks that can be cancelled when callbacks complete or clients disconnect.

**Decision**:
Use `ScheduledExecutorService` with single thread pool for timeout scheduling:

```java
@Bean
public ScheduledExecutorService timeoutScheduler() {
    return Executors.newScheduledThreadPool(1);
}

ScheduledFuture<?> future = timeoutScheduler.schedule(
    () -> handleTimeout(correlationId),
    timeoutMs,
    TimeUnit.MILLISECONDS
);

// Cancel on completion or disconnect
future.cancel(false);
```

**Alternatives Considered**:

1. **Reactor's `timeout()` operator**
   - Pros: Reactive-native
   - Cons: Applied to wrong operation (HTTP call, not callbacks)

2. **Spring's `@Scheduled` annotations**
   - Pros: Declarative
   - Cons: Fixed schedules, not dynamic timeouts

3. **CompletableFuture delayed execution**
   - Pros: Modern Java API
   - Cons: Less control, harder cancellation

**Rationale**:
- Precise control over timeout scheduling
- Easy cancellation via `ScheduledFuture`
- Lightweight (single thread suffices)
- Clear separation from reactive streams
- Standard Java concurrency API

**Consequences**:
- ✅ Precise timeout control
- ✅ Easy cancellation
- ✅ Minimal overhead
- ✅ Clear code
- ⚠️ Must manage ScheduledFuture lifecycle
- ⚠️ Requires proper shutdown
- ⚠️ Not reactive (but acceptable for scheduling)

---

## Technology Stack Summary

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| **Language** | Java 17 | Modern LTS, records, pattern matching |
| **Framework** | Spring Boot 3.2.0 | Latest version, comprehensive ecosystem |
| **Reactive** | Spring WebFlux + Project Reactor | Non-blocking I/O, SSE support |
| **SSE** | Sinks.Many<ServerSentEvent<?>> | Native Spring WebFlux SSE |
| **HTTP Client** | WebClient | Reactive, non-blocking |
| **Timeout** | ScheduledExecutorService | Precise scheduling, easy cancellation |
| **Concurrency** | AtomicInteger, Disposable | Thread-safe counters, cancellation |
| **Build** | Maven 3.9+ | Standard Java build tool |
| **Containerization** | Docker + Docker Compose | Easy deployment, orchestration |
| **Testing** | JUnit 5, MockWebServer | Modern testing, HTTP mocking |

---

## Future Considerations

### Information Location Index (ILI) Integration

**Status**: Planned

**Description**: Replace fixed 3-resource demo with dynamic resource discovery:

```java
public interface InformationLocationIndex {
    List<ResourceEndpoint> findResourcesForPatient(String patientId);
}
```

**Impact**:
- Remove `delays` parameter (demo-only)
- Dynamic resource count per patient
- Real-world resource discovery
- Geographic distribution support

### Circuit Breaker Pattern

**Status**: Proposed

**Description**: Implement circuit breaker for failing resources to prevent cascading failures:

```java
@CircuitBreaker(name = "resourceService", fallbackMethod = "fallback")
public Mono<Callback> callResource(String url, Request request) {
    // ...
}
```

**Benefits**:
- Fail fast when resources are down
- Prevent resource exhaustion
- Automatic recovery attempts

### Retry Logic

**Status**: Proposed

**Description**: Add configurable retry for transient failures:

```java
webClient.post()
    .retrieve()
    .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))
```

**Considerations**:
- Idempotent operations only
- Total timeout must account for retries
- Exponential backoff to prevent overwhelming failing services

### Metrics and Monitoring

**Status**: Proposed

**Description**: Add observability via Micrometer:

```java
@Timed(value = "aggregator.calls", extraTags = {"strategy", "sse"})
public Mono<JournalResponse> aggregateJournals(JournalRequest request) {
    // ...
}
```

**Metrics to Track**:
- Request duration by strategy
- Resource callback latencies
- Timeout frequency
- Error rates by type
- Active SSE connections

---

## References

- [Spring WebFlux Documentation](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [Project Reactor Reference](https://projectreactor.io/docs/core/release/reference/)
- [SSE HTML Standard](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [Reactive Streams Specification](https://www.reactive-streams.org/)
