# System State and Error Handling

## System States

This document describes the various states and transitions in the aggregator system.

### SSE Connection Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Idle: System Ready
    
    Idle --> Registering: POST /journals received
    Registering --> WaitingForSSE: Return correlationId
    
    WaitingForSSE --> Streaming: Client opens SSE connection
    
    Streaming --> CollectingCallbacks: Register expected callbacks
    CollectingCallbacks --> SchedulingTimeout: Schedule timeout task
    
    SchedulingTimeout --> AwaitingCallbacks: Wait for callbacks
    
    AwaitingCallbacks --> ReceivingCallback: Callback arrives
    ReceivingCallback --> EmittingEvent: Send callback event
    EmittingEvent --> CheckingCompletion: Increment respondent counter
    
    CheckingCompletion --> AwaitingCallbacks: More callbacks expected
    CheckingCompletion --> Completing: All callbacks received
    
    AwaitingCallbacks --> TimedOut: Timeout expires
    TimedOut --> MarkingMissing: Mark missing as TIMEOUT
    MarkingMissing --> EmittingTimeouts: Send TIMEOUT events
    EmittingTimeouts --> PartialComplete: Send summary with partial results
    
    AwaitingCallbacks --> Disconnected: Client closes connection
    Disconnected --> Cancelling: Cancel resource calls
    Cancelling --> CancellingTimeout: Cancel timeout task
    CancellingTimeout --> Cleanup: Remove sink
    
    Completing --> CancellingTimeout2: Cancel timeout task
    CancellingTimeout2 --> EmittingSummary: Send summary event
    EmittingSummary --> ClosingConnection: Complete sink
    
    PartialComplete --> ClosingConnection
    ClosingConnection --> [*]: Connection closed
    Cleanup --> [*]: Cleanup complete
```

### Resource Call States

```mermaid
stateDiagram-v2
    [*] --> Initiating: Create WebClient request
    
    Initiating --> Calling: POST /journals
    
    Calling --> Accepted: 200 OK (delay >= 0)
    Calling --> Rejected: 401 Unauthorized (delay == -1)
    Calling --> ConnectionError: Connection failed
    Calling --> HttpTimeout: HTTP timeout
    
    Accepted --> Processing: Resource processes async
    Rejected --> EmitRejected: Send REJECTED event
    ConnectionError --> EmitError: Send CONNECTION_CLOSED event
    HttpTimeout --> EmitTimeout: Send TIMEOUT event
    
    Processing --> CallingBack: POST /aggregate/callback
    
    CallingBack --> CallbackReceived: Callback arrives
    CallingBack --> CallbackTimeout: Timeout before callback
    
    CallbackReceived --> [*]: Success
    CallbackTimeout --> [*]: Marked as timeout
    EmitRejected --> [*]: Rejection recorded
    EmitError --> [*]: Error recorded
    EmitTimeout --> [*]: Timeout recorded
```

### Wait-for-Everyone Strategy States

```mermaid
stateDiagram-v2
    [*] --> Initiating: POST /journals received
    
    Initiating --> CallingResources: POST /journals/direct (all resources)
    
    CallingResources --> AwaitingResponses: Parallel calls in flight
    
    state AwaitingResponses {
        [*] --> PendingR1: Resource 1 pending
        [*] --> PendingR2: Resource 2 pending
        [*] --> PendingR3: Resource 3 pending
        
        PendingR1 --> CompletedR1: Response OK
        PendingR2 --> CompletedR2: Response OK
        PendingR3 --> CompletedR3: Response OK
        
        PendingR1 --> RejectedR1: Status REJECTED
        PendingR2 --> RejectedR2: Status REJECTED
        PendingR3 --> RejectedR3: Status REJECTED
        
        PendingR1 --> TimeoutR1: Call timeout
        PendingR2 --> TimeoutR2: Call timeout
        PendingR3 --> TimeoutR3: Call timeout
    }
    
    AwaitingResponses --> Aggregating: All responses received
    
    Aggregating --> CountingRespondents: Count successful responses
    CountingRespondents --> CountingErrors: Count errors
    CountingErrors --> CollectingNotes: Collect all notes
    CollectingNotes --> BuildingResponse: Build aggregated response
    
    BuildingResponse --> Returning: Return complete response
    Returning --> [*]: Request complete
```

---

## Error Handling Matrix

### Event Types and Classification

| Event Type | HTTP Status | Counted As | Description | Action |
|------------|-------------|------------|-------------|--------|
| **ok** | 200 | Respondent | Successful callback with notes | Emit callback event, increment respondents |
| **REJECTED** | 401 | Neither | Business rejection (delay=-1) | Emit REJECTED event immediately, don't count as error |
| **TIMEOUT** | N/A | Error | Resource didn't callback in time | Emit TIMEOUT event, increment errors |
| **CONNECTION_CLOSED** | N/A | Error | Connection closed prematurely | Emit CONNECTION_CLOSED event, increment errors |
| **ERROR** | 500+ | Error | Other error conditions | Emit ERROR event, increment errors |

### Error Response Patterns

```mermaid
flowchart TD
    Start[Resource Call Initiated]
    
    Start --> CheckResponse{Check Response}
    
    CheckResponse -->|200 OK| Accepted[Accepted]
    CheckResponse -->|401 Unauthorized| Rejected[Rejected]
    CheckResponse -->|5xx Error| Error[Error]
    CheckResponse -->|Connection Lost| ConnLost[Connection Closed]
    CheckResponse -->|Timeout| Timeout[Timeout]
    
    Accepted --> WaitCallback[Wait for Callback]
    
    WaitCallback --> CallbackArrives{Callback Status}
    CallbackArrives -->|success| Success[✅ Respondent]
    CallbackArrives -->|timeout| CallbackTimeout[❌ Timeout Error]
    
    Rejected --> EmitRejected[⚠️ Emit REJECTED Event]
    Error --> EmitError[❌ Emit ERROR Event]
    ConnLost --> EmitConnClosed[❌ Emit CONNECTION_CLOSED Event]
    Timeout --> EmitTimeout[❌ Emit TIMEOUT Event]
    
    Success --> IncrementRespondents[Increment Respondents Counter]
    CallbackTimeout --> IncrementErrors[Increment Errors Counter]
    EmitError --> IncrementErrors
    EmitConnClosed --> IncrementErrors
    EmitTimeout --> IncrementErrors
    
    EmitRejected --> NoCount[No Counter Change]
    
    IncrementRespondents --> IncrementReceived[Increment Received Counter]
    IncrementErrors --> IncrementReceived
    NoCount --> IncrementReceived
    
    IncrementReceived --> CheckComplete{All Received?}
    CheckComplete -->|Yes| EmitSummary[Emit Summary Event]
    CheckComplete -->|No| Continue[Continue Waiting]
    
    EmitSummary --> CloseConnection[Close SSE Connection]
    CloseConnection --> End[Complete]
    
    style Success fill:#90EE90
    style CallbackTimeout fill:#FFB6C1
    style EmitRejected fill:#FFE4B5
    style EmitError fill:#FFB6C1
    style EmitConnClosed fill:#FFB6C1
    style EmitTimeout fill:#FFB6C1
    style IncrementRespondents fill:#90EE90
    style IncrementErrors fill:#FFB6C1
    style NoCount fill:#FFE4B5
```

---

## Timeout Behavior

### Timeline Visualization

```
SSE Strategy (10s timeout, delays: 1000,2000,15000):

T=0ms     Client sends request
T=0ms     Aggregator calls resources in parallel
T=10ms    All resources respond: 200, 200, 200
T=10ms    Aggregator returns {respondents: 3, correlationId: "abc"}
T=20ms    Client opens SSE connection
T=30ms    Timeout task scheduled (10000ms)
          
T=1000ms  Resource-1 POSTs callback
T=1000ms  → SSE event: callback (resource-1, 3 notes)
          
T=2000ms  Resource-2 POSTs callback  
T=2000ms  → SSE event: callback (resource-2, 3 notes)
          
T=10030ms ⏰ TIMEOUT EXPIRES
T=10030ms → SSE event: TIMEOUT (resource-3)
T=10030ms → SSE event: summary (respondents=2, errors=1)
T=10030ms → SSE connection closes
          
T=15000ms Resource-3 POSTs callback (late)
T=15000ms → Sink not found, callback acknowledged but discarded
```

### Timeout Configuration

```mermaid
flowchart LR
    Client[Client Request<br/>timeoutMs: 10000]
    
    Client --> Validate{Validate}
    
    Validate -->|timeout ≤ max| UseClient[Use Client Timeout<br/>10000ms]
    Validate -->|timeout > max| UseMax[Cap at Maximum<br/>27000ms]
    Validate -->|timeout null| UseDefault[Use Default<br/>10000ms]
    
    UseClient --> Schedule[Schedule Timeout Task]
    UseMax --> Schedule
    UseDefault --> Schedule
    
    Schedule --> Monitor[Monitor Callbacks]
    
    Monitor --> Complete{All Callbacks<br/>Received?}
    
    Complete -->|Yes| Cancel[Cancel Timeout Task]
    Complete -->|No| Wait[Wait for Timeout]
    
    Wait --> Expired{Timeout<br/>Expired?}
    
    Expired -->|Yes| PartialComplete[Complete with Partial Results]
    Expired -->|No| Monitor
    
    Cancel --> FullComplete[Complete with Full Results]
    
    style UseClient fill:#90EE90
    style UseMax fill:#FFE4B5
    style UseDefault fill:#E0E0E0
    style Cancel fill:#90EE90
    style FullComplete fill:#90EE90
    style PartialComplete fill:#FFE4B5
```

---

## Client Disconnect Scenarios

### Clean Disconnect Flow

```mermaid
sequenceDiagram
    participant Browser
    participant SSE Stream
    participant SinkManager
    participant ResourceCalls
    participant TimeoutTask

    Note over Browser: User closes tab
    Browser->>SSE Stream: Close connection
    SSE Stream->>SSE Stream: Trigger doOnCancel()
    
    par Parallel Cleanup
        SSE Stream->>ResourceCalls: dispose()
        SSE Stream->>TimeoutTask: cancel()
        SSE Stream->>SinkManager: removeSink(correlationId)
    end
    
    Note over ResourceCalls: Pending HTTP calls cancelled
    Note over TimeoutTask: Scheduled task cancelled
    Note over SinkManager: Sink removed from registry
    
    opt Late Callback Arrives
        ResourceCalls->>SinkManager: POST /aggregate/callback
        SinkManager->>SinkManager: Sink not found
        SinkManager-->>ResourceCalls: 200 OK (acknowledged)
        Note over SinkManager: Callback discarded safely
    end
```

### Disconnect During Various States

```mermaid
stateDiagram-v2
    [*] --> WaitingForConnection: Request dispatched
    
    WaitingForConnection --> Disconnect1: ❌ Disconnect
    Disconnect1 --> NoCleanup1: No SSE connection yet
    NoCleanup1 --> [*]: Resources complete anyway
    
    WaitingForConnection --> Connected: Client connects
    
    Connected --> AwaitingCallbacks: Callbacks pending
    
    AwaitingCallbacks --> Disconnect2: ❌ Disconnect
    Disconnect2 --> Cleanup2: Cancel calls + timeout
    Cleanup2 --> [*]: Cleaned up
    
    AwaitingCallbacks --> ReceivingCallbacks: Callbacks arrive
    
    ReceivingCallbacks --> Disconnect3: ❌ Disconnect
    Disconnect3 --> Cleanup3: Cancel remaining + timeout
    Cleanup3 --> [*]: Partial results lost
    
    ReceivingCallbacks --> AllReceived: All callbacks done
    AllReceived --> Completed: Send summary
    Completed --> [*]: Normal completion
    
    style Disconnect1 fill:#FFB6C1
    style Disconnect2 fill:#FFB6C1
    style Disconnect3 fill:#FFB6C1
    style Completed fill:#90EE90
```

---

## Concurrent Access Patterns

### Thread Safety

```mermaid
flowchart TD
    subgraph "Main Thread"
        Request[HTTP Request]
        Response[HTTP Response]
    end
    
    subgraph "Reactor Event Loop"
        WebClient[WebClient Calls]
        SinkEmitter[Sink Event Emitter]
    end
    
    subgraph "Callback Thread Pool"
        Callback1[Resource Callback 1]
        Callback2[Resource Callback 2]
        Callback3[Resource Callback 3]
    end
    
    subgraph "Scheduled Thread"
        TimeoutTask[Timeout Task]
    end
    
    subgraph "Shared State (Thread-Safe)"
        SinkInfo[SinkInfo<br/>AtomicInteger: received<br/>AtomicInteger: respondents<br/>AtomicInteger: errors]
        SinkRegistry[SinkManager<br/>ConcurrentHashMap]
    end
    
    Request --> WebClient
    WebClient --> Response
    
    Callback1 --> SinkInfo
    Callback2 --> SinkInfo
    Callback3 --> SinkInfo
    
    SinkInfo --> SinkEmitter
    SinkEmitter --> Response
    
    TimeoutTask --> SinkInfo
    
    SinkInfo -.-> SinkRegistry
    
    style SinkInfo fill:#FFE4B5
    style SinkRegistry fill:#FFE4B5
```

### Atomic Operations

All counter updates use `AtomicInteger` for thread-safe operations:

```java
// ✅ Thread-safe increment
int currentCount = sinkInfo.getRespondents().incrementAndGet();

// ✅ Thread-safe read
int total = sinkInfo.getReceived().get();

// ✅ Compare total vs. expected
if (received == expectedCallbacks) {
    completeWithSummary();
}
```

### Race Condition Handling

**Scenario**: Timeout expires while callback is being processed

```mermaid
sequenceDiagram
    participant Callback as Callback Thread
    participant Timeout as Timeout Thread
    participant Sink as SinkInfo

    par Concurrent Access
        Callback->>Sink: incrementAndGet() → 2
        Timeout->>Sink: get() → 1 (stale)
    end
    
    Callback->>Sink: Check: received (2) == expected (2)?
    Callback->>Sink: ✅ Yes, complete with full results
    Callback->>Sink: Cancel timeout task
    
    Note over Timeout: Task cancelled, won't execute
    
    alt Timeout already started
        Timeout->>Sink: Check: received (2) == expected (2)?
        Timeout->>Sink: ✅ Already complete, skip timeout logic
    end
```

**Protection**: `ScheduledFuture.cancel()` + idempotent completion check

---

## Summary Message Examples

### All Resources Succeed

```json
{
  "source": "AGGREGATOR",
  "patientId": "patient-123",
  "correlationId": "abc-123",
  "status": "COMPLETE",
  "respondents": 3,
  "errors": 0,
  "notes": null
}
```

### One Rejection (401), Two Success

```json
{
  "source": "AGGREGATOR",
  "patientId": "patient-123",
  "correlationId": "abc-123",
  "status": "COMPLETE",
  "respondents": 2,
  "errors": 0,
  "notes": null
}
```

### One Timeout, Two Success

```json
{
  "source": "AGGREGATOR",
  "patientId": "patient-123",
  "correlationId": "abc-123",
  "status": "COMPLETE",
  "respondents": 2,
  "errors": 1,
  "notes": null
}
```

### All Resources Timeout

```json
{
  "source": "AGGREGATOR",
  "patientId": "patient-123",
  "correlationId": "abc-123",
  "status": "COMPLETE",
  "respondents": 0,
  "errors": 3,
  "notes": null
}
```

---

## Monitoring Points

### Key Metrics to Track

1. **Request Metrics**
   - Total requests by strategy (SSE vs. synchronous)
   - Request duration (p50, p95, p99)
   - Timeout rate

2. **Resource Metrics**
   - Response times per resource
   - Success rate per resource
   - Rejection rate (401) per resource
   - Error rate per resource

3. **SSE Metrics**
   - Active SSE connections
   - Connection duration
   - Events emitted per connection
   - Client disconnects

4. **Callback Metrics**
   - Callback latency
   - Late callbacks (after timeout)
   - Callback errors

5. **Error Metrics**
   - Errors by type (TIMEOUT, CONNECTION_CLOSED, ERROR)
   - Error rate trend
   - Partial completion rate

### Health Checks

```java
// Aggregator health
GET /actuator/health
{
  "status": "UP",
  "components": {
    "sseManager": {
      "status": "UP",
      "details": {
        "activeSinks": 5,
        "totalProcessed": 1234
      }
    },
    "resourceConnectivity": {
      "status": "UP",
      "details": {
        "resource-1": "UP",
        "resource-2": "UP",
        "resource-3": "DOWN"
      }
    }
  }
}
```
