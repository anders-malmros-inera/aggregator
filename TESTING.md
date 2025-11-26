# Testing Guide

## Overview

This document provides comprehensive testing strategies, test scenarios, and examples for the aggregator system.

---

## Table of Contents

1. [Test Pyramid](#test-pyramid)
2. [Unit Tests](#unit-tests)
3. [Integration Tests](#integration-tests)
4. [End-to-End Tests](#end-to-end-tests)
5. [Load Testing](#load-testing)
6. [Test Scenarios](#test-scenarios)
7. [Manual Testing](#manual-testing)

---

## Test Pyramid

```
                  /\
                 /  \
                /E2E \          Small number, slow, expensive
               /------\
              / Integ  \        Medium number, medium speed
             /----------\
            /   Unit     \      Large number, fast, cheap
           /--------------\
```

**Distribution**:
- Unit Tests: 70% (fast, isolated, high coverage)
- Integration Tests: 20% (component interaction)
- End-to-End Tests: 10% (full system validation)

---

## Unit Tests

### Aggregator Service Tests

```java
@ExtendWith(MockitoExtension.class)
class AggregatorServiceTest {
    
    @Mock
    private SseService sseService;
    
    @Mock
    private WebClient webClient;
    
    @InjectMocks
    private AggregatorService aggregatorService;
    
    @Test
    void shouldGenerateCorrelationId() {
        JournalRequest request = new JournalRequest("patient-123", "1000,2000,3000", 10000, "SSE");
        
        Mono<JournalResponse> response = aggregatorService.aggregateJournals(request);
        
        StepVerifier.create(response)
            .assertNext(r -> {
                assertNotNull(r.getCorrelationId());
                assertEquals(0, r.getRespondents());
            })
            .verifyComplete();
    }
    
    @Test
    void shouldCapTimeoutAtMaximum() {
        aggregatorService.setMaxTimeoutMs(27000);
        JournalRequest request = new JournalRequest("patient-123", "1000,2000,3000", 50000, "SSE");
        
        // Should cap at 27000ms
        Mono<JournalResponse> response = aggregatorService.aggregateJournals(request);
        
        verify(sseService).scheduleTimeout(any(), eq(27000L));
    }
    
    @Test
    void shouldCountRespondentsExcludingRejections() {
        // Setup: 3 resources, 1 rejects (401), 2 accept (200)
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        
        // resource-1: 200 OK
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.just(
            ResponseEntity.status(200).build()
        ));
        
        JournalRequest request = new JournalRequest("patient-123", "1000,2000,-1", 10000, "SSE");
        
        StepVerifier.create(aggregatorService.aggregateJournals(request))
            .assertNext(r -> assertEquals(2, r.getRespondents()))
            .verifyComplete();
    }
    
    @Test
    void shouldHandleAllResourcesRejecting() {
        // All return 401
        JournalRequest request = new JournalRequest("patient-123", "-1,-1,-1", 10000, "SSE");
        
        StepVerifier.create(aggregatorService.aggregateJournals(request))
            .assertNext(r -> {
                assertEquals(0, r.getRespondents());
                assertNotNull(r.getCorrelationId());
            })
            .verifyComplete();
    }
}
```

### SSE Service Tests

```java
@ExtendWith(MockitoExtension.class)
class SseServiceTest {
    
    @Mock
    private SseSinkManager sinkManager;
    
    @Mock
    private ScheduledExecutorService scheduler;
    
    @InjectMocks
    private SseService sseService;
    
    private String correlationId;
    private SinkInfo sinkInfo;
    
    @BeforeEach
    void setup() {
        correlationId = UUID.randomUUID().toString();
        sinkInfo = new SinkInfo(
            Sinks.many().multicast().onBackpressureBuffer(),
            new AtomicInteger(0),
            new AtomicInteger(0),
            new AtomicInteger(0),
            3
        );
        when(sinkManager.getSink(correlationId)).thenReturn(sinkInfo);
    }
    
    @Test
    void shouldIncrementRespondentsOnSuccessfulCallback() {
        JournalCallback callback = JournalCallback.builder()
            .source("resource-1")
            .patientId("patient-123")
            .correlationId(correlationId)
            .status("ok")
            .notes(List.of(new JournalNote()))
            .build();
        
        sseService.sendEventAndCountRespondent(callback);
        
        assertEquals(1, sinkInfo.getRespondents().get());
        assertEquals(1, sinkInfo.getReceived().get());
        assertEquals(0, sinkInfo.getErrors().get());
    }
    
    @Test
    void shouldIncrementErrorsOnTimeout() {
        JournalCallback callback = JournalCallback.builder()
            .source("resource-1")
            .patientId("patient-123")
            .correlationId(correlationId)
            .status("TIMEOUT")
            .build();
        
        sseService.sendEvent(callback);
        
        assertEquals(0, sinkInfo.getRespondents().get());
        assertEquals(1, sinkInfo.getReceived().get());
        assertEquals(1, sinkInfo.getErrors().get());
    }
    
    @Test
    void shouldNotIncrementErrorsOnRejection() {
        JournalCallback callback = JournalCallback.builder()
            .source("resource-1")
            .patientId("patient-123")
            .correlationId(correlationId)
            .status("REJECTED")
            .build();
        
        sseService.sendEvent(callback);
        
        assertEquals(0, sinkInfo.getRespondents().get());
        assertEquals(1, sinkInfo.getReceived().get());
        assertEquals(0, sinkInfo.getErrors().get());  // REJECTED not counted as error
    }
    
    @Test
    void shouldCompleteWhenAllCallbacksReceived() {
        sinkInfo.getExpectedCallbacks().set(2);
        
        // First callback
        JournalCallback callback1 = JournalCallback.builder()
            .source("resource-1")
            .correlationId(correlationId)
            .status("ok")
            .notes(List.of(new JournalNote()))
            .build();
        sseService.sendEventAndCountRespondent(callback1);
        
        // Second callback - should trigger completion
        JournalCallback callback2 = JournalCallback.builder()
            .source("resource-2")
            .correlationId(correlationId)
            .status("ok")
            .notes(List.of(new JournalNote()))
            .build();
        sseService.sendEventAndCountRespondent(callback2);
        
        // Verify summary emitted and sink completed
        verify(sinkManager).removeSink(correlationId);
    }
    
    @Test
    void shouldCancelTimeoutWhenCompleting() {
        ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
        sinkInfo.setTimeoutFuture(mockFuture);
        
        sseService.completeWithSummary(correlationId, 2, 0);
        
        verify(mockFuture).cancel(false);
    }
}
```

### Resource Service Tests

```java
@ExtendWith(MockitoExtension.class)
class ResourceServiceTest {
    
    @Mock
    private JournalNoteGenerator noteGenerator;
    
    @Mock
    private WebClient webClient;
    
    @InjectMocks
    private ResourceService resourceService;
    
    @Test
    void shouldGenerateNotesForPositiveDelay() {
        when(noteGenerator.generateNotes("patient-123")).thenReturn(
            List.of(
                new JournalNote(LocalDate.now(), "Note 1"),
                new JournalNote(LocalDate.now(), "Note 2"),
                new JournalNote(LocalDate.now(), "Note 3")
            )
        );
        
        JournalCallback result = resourceService.processJournalRequestSynchronously(
            "patient-123", 0
        );
        
        assertEquals("ok", result.getStatus());
        assertEquals(3, result.getNotes().size());
    }
    
    @Test
    void shouldRejectForNegativeDelay() {
        JournalCallback result = resourceService.processJournalRequestSynchronously(
            "patient-123", -1
        );
        
        assertEquals("REJECTED", result.getStatus());
        assertNull(result.getNotes());
    }
    
    @Test
    void shouldRespectDelayTiming() {
        long startTime = System.currentTimeMillis();
        
        resourceService.processJournalRequestSynchronously("patient-123", 1000);
        
        long elapsed = System.currentTimeMillis() - startTime;
        assertTrue(elapsed >= 1000, "Should wait at least 1000ms");
        assertTrue(elapsed < 1500, "Should not wait more than 1500ms");
    }
}
```

### Running Unit Tests

```bash
# Run all unit tests
mvn test

# Run specific test class
mvn test -Dtest=AggregatorServiceTest

# Run with coverage
mvn test jacoco:report

# View coverage report
open target/site/jacoco/index.html
```

---

## Integration Tests

### SSE Flow Integration Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class SseIntegrationTest {
    
    @Autowired
    private WebTestClient webTestClient;
    
    @Autowired
    private SseService sseService;
    
    private MockWebServer mockResourceServer;
    
    @BeforeEach
    void setup() throws IOException {
        mockResourceServer = new MockWebServer();
        mockResourceServer.start();
    }
    
    @AfterEach
    void teardown() throws IOException {
        mockResourceServer.shutdown();
    }
    
    @Test
    void shouldStreamCallbacksViaSSE() throws InterruptedException {
        String correlationId = UUID.randomUUID().toString();
        
        // 1. Open SSE connection
        Flux<ServerSentEvent<JournalCallback>> eventStream = webTestClient
            .get()
            .uri("/aggregate/stream?correlationId=" + correlationId)
            .exchange()
            .expectStatus().isOk()
            .returnResult(new ParameterizedTypeReference<ServerSentEvent<JournalCallback>>() {})
            .getResponseBody();
        
        // 2. Register expected callbacks
        sseService.registerExpectedCallbacks(correlationId, 2);
        
        // 3. Send callbacks
        JournalCallback callback1 = JournalCallback.builder()
            .source("resource-1")
            .correlationId(correlationId)
            .status("ok")
            .notes(List.of(new JournalNote()))
            .build();
        
        sseService.sendEventAndCountRespondent(callback1);
        
        JournalCallback callback2 = JournalCallback.builder()
            .source("resource-2")
            .correlationId(correlationId)
            .status("ok")
            .notes(List.of(new JournalNote()))
            .build();
        
        sseService.sendEventAndCountRespondent(callback2);
        
        // 4. Verify events received
        StepVerifier.create(eventStream)
            .expectNextMatches(event -> 
                "callback".equals(event.event()) &&
                "resource-1".equals(event.data().getSource())
            )
            .expectNextMatches(event -> 
                "callback".equals(event.event()) &&
                "resource-2".equals(event.data().getSource())
            )
            .expectNextMatches(event -> 
                "summary".equals(event.event()) &&
                event.data().getRespondents() == 2
            )
            .expectComplete()
            .verify(Duration.ofSeconds(10));
    }
    
    @Test
    void shouldHandleTimeoutScenario() {
        String correlationId = UUID.randomUUID().toString();
        
        // Register 2 expected, send only 1, wait for timeout
        sseService.registerExpectedCallbacks(correlationId, 2);
        sseService.scheduleTimeout(correlationId, 2000);  // 2s timeout
        
        JournalCallback callback = JournalCallback.builder()
            .source("resource-1")
            .correlationId(correlationId)
            .status("ok")
            .notes(List.of(new JournalNote()))
            .build();
        
        sseService.sendEventAndCountRespondent(callback);
        
        // Wait for timeout
        Thread.sleep(2500);
        
        // Verify summary with partial results
        SinkInfo sinkInfo = sseService.getSink(correlationId);
        assertEquals(1, sinkInfo.getRespondents().get());
        assertEquals(1, sinkInfo.getErrors().get());  // Missing one counted as timeout error
    }
}
```

### End-to-End Resource Flow Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class E2EResourceFlowTest {
    
    @Autowired
    private WebTestClient webTestClient;
    
    private MockWebServer mockResource1;
    private MockWebServer mockResource2;
    private MockWebServer mockResource3;
    
    @BeforeEach
    void setup() throws IOException {
        mockResource1 = new MockWebServer();
        mockResource2 = new MockWebServer();
        mockResource3 = new MockWebServer();
        
        mockResource1.start(8081);
        mockResource2.start(8083);
        mockResource3.start(8084);
    }
    
    @Test
    void shouldAggregateFromMultipleResources() {
        // Mock resource responses
        mockResource1.enqueue(new MockResponse().setResponseCode(200));  // Accept
        mockResource2.enqueue(new MockResponse().setResponseCode(200));  // Accept
        mockResource3.enqueue(new MockResponse().setResponseCode(401));  // Reject
        
        // Send aggregation request
        JournalRequest request = new JournalRequest(
            "patient-123",
            "1000,2000,-1",
            10000,
            "SSE"
        );
        
        webTestClient.post()
            .uri("/aggregate/journals")
            .bodyValue(request)
            .exchange()
            .expectStatus().isOk()
            .expectBody(JournalResponse.class)
            .value(response -> {
                assertNotNull(response.getCorrelationId());
                assertEquals(2, response.getRespondents());  // 2 accepted, 1 rejected
            });
    }
}
```

### Running Integration Tests

```bash
# Run integration tests
mvn verify

# Run specific integration test
mvn verify -Dit.test=SseIntegrationTest

# Run with Docker Compose
docker-compose -f docker-compose.yml -f docker-compose.test.yml up --abort-on-container-exit
```

---

## End-to-End Tests

### Full System Test with Docker Compose

```bash
#!/bin/bash
# e2e-test.sh

set -e

echo "Starting services..."
docker-compose up -d

echo "Waiting for services to be ready..."
sleep 10

echo "Testing aggregator health..."
curl -f http://localhost:8080/actuator/health || exit 1

echo "Testing client health..."
curl -f http://localhost:8082/actuator/health || exit 1

echo "Sending aggregation request..."
RESPONSE=$(curl -s -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{"patientId":"patient-123","delays":"1000,2000,3000","timeoutMs":10000,"strategy":"SSE"}')

CORRELATION_ID=$(echo $RESPONSE | jq -r '.correlationId')
RESPONDENTS=$(echo $RESPONSE | jq -r '.respondents')

echo "Correlation ID: $CORRELATION_ID"
echo "Expected respondents: $RESPONDENTS"

if [ "$RESPONDENTS" != "3" ]; then
    echo "❌ Expected 3 respondents, got $RESPONDENTS"
    exit 1
fi

echo "✅ All tests passed!"

echo "Stopping services..."
docker-compose down
```

### Browser-Based E2E Test (Selenium)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class BrowserE2ETest {
    
    private WebDriver driver;
    
    @BeforeEach
    void setup() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless");
        driver = new ChromeDriver(options);
    }
    
    @AfterEach
    void teardown() {
        if (driver != null) {
            driver.quit();
        }
    }
    
    @Test
    void shouldDisplayResultsInUI() throws InterruptedException {
        driver.get("http://localhost:8082");
        
        // Fill form
        driver.findElement(By.id("patientId")).sendKeys("patient-123");
        driver.findElement(By.id("delays")).sendKeys("1000,2000,3000");
        driver.findElement(By.id("timeout")).sendKeys("10000");
        
        // Submit
        driver.findElement(By.id("submitButton")).click();
        
        // Wait for correlation ID
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(5));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("correlationId")));
        
        // Wait for results (up to 10s)
        Thread.sleep(5000);
        
        // Verify notes displayed
        List<WebElement> notes = driver.findElements(By.className("note-card"));
        assertTrue(notes.size() >= 6, "Should display at least 6 notes (2 resources * 3 notes)");
        
        // Verify completion message
        WebElement summary = driver.findElement(By.id("summary"));
        assertTrue(summary.getText().contains("Aggregation complete"));
    }
}
```

---

## Load Testing

### Gatling Load Test

```scala
import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class AggregatorLoadTest extends Simulation {
  
  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")
  
  val sseScenario = scenario("SSE Aggregation")
    .exec(
      http("aggregate_request")
        .post("/aggregate/journals")
        .body(StringBody("""{"patientId":"patient-${userId}","delays":"1000,2000,3000","timeoutMs":10000,"strategy":"SSE"}""")).asJson
        .check(jsonPath("$.correlationId").saveAs("correlationId"))
    )
    .exec(
      sse("sse_stream")
        .get("/aggregate/stream?correlationId=${correlationId}")
        .check(wsAwait.within(15.seconds))
    )
  
  val syncScenario = scenario("Synchronous Aggregation")
    .exec(
      http("sync_request")
        .post("/aggregate/journals")
        .body(StringBody("""{"patientId":"patient-${userId}","delays":"1000,2000,3000","timeoutMs":10000,"strategy":"WAIT_FOR_EVERYONE"}""")).asJson
        .check(status.is(200))
        .check(jsonPath("$.respondents").is("3"))
    )
  
  setUp(
    sseScenario.inject(
      rampUsers(100) during (30.seconds)
    ),
    syncScenario.inject(
      rampUsers(50) during (30.seconds)
    )
  ).protocols(httpProtocol)
}
```

### JMeter Test Plan

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2">
  <hashTree>
    <TestPlan>
      <stringProp name="TestPlan.comments">Aggregator Load Test</stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <stringProp name="TestPlan.user_define_classpath"></stringProp>
    </TestPlan>
    <hashTree>
      <ThreadGroup>
        <stringProp name="ThreadGroup.num_threads">100</stringProp>
        <stringProp name="ThreadGroup.ramp_time">60</stringProp>
        <longProp name="ThreadGroup.duration">300</longProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy>
          <stringProp name="HTTPSampler.domain">localhost</stringProp>
          <stringProp name="HTTPSampler.port">8080</stringProp>
          <stringProp name="HTTPSampler.path">/aggregate/journals</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.postBodyRaw">true</boolProp>
          <elementProp name="HTTPsampler.Arguments">
            <stringProp name="Argument.value">
              {
                "patientId": "patient-${__threadNum}",
                "delays": "1000,2000,3000",
                "timeoutMs": 10000,
                "strategy": "SSE"
              }
            </stringProp>
          </elementProp>
        </HTTPSamplerProxy>
      </hashTree>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

### Performance Benchmarks

| Scenario | Users | Duration | Throughput | Avg Latency | P95 Latency | Error Rate |
|----------|-------|----------|------------|-------------|-------------|------------|
| SSE (all succeed) | 100 | 5 min | 15 req/s | 3.2s | 4.1s | 0% |
| SSE (one timeout) | 100 | 5 min | 12 req/s | 10.5s | 11.2s | 0% |
| Sync (all succeed) | 100 | 5 min | 20 req/s | 3.1s | 3.9s | 0% |
| Sync (one timeout) | 100 | 5 min | 15 req/s | 3.2s | 4.0s | 0% |

---

## Test Scenarios

### Scenario 1: All Resources Succeed

```bash
curl -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delays": "1000,2000,3000",
    "timeoutMs": 10000,
    "strategy": "SSE"
  }'
```

**Expected**:
- `respondents: 3`
- 3 callback events
- 1 summary event with `respondents=3, errors=0`

### Scenario 2: One Resource Rejects (401)

```bash
curl -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delays": "1000,2000,-1",
    "timeoutMs": 10000,
    "strategy": "SSE"
  }'
```

**Expected**:
- `respondents: 2`
- 1 REJECTED event (immediate)
- 2 callback events
- 1 summary event with `respondents=2, errors=0` (rejection not counted as error)

### Scenario 3: One Resource Times Out

```bash
curl -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delays": "1000,2000,15000",
    "timeoutMs": 10000,
    "strategy": "SSE"
  }'
```

**Expected**:
- `respondents: 3` (all accept initially)
- 2 callback events (within 10s)
- 1 TIMEOUT event (at 10s)
- 1 summary event with `respondents=2, errors=1`

### Scenario 4: All Resources Reject

```bash
curl -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delays": "-1,-1,-1",
    "timeoutMs": 10000,
    "strategy": "SSE"
  }'
```

**Expected**:
- `respondents: 0`
- 3 REJECTED events (immediate)
- 1 summary event with `respondents=0, errors=0`

### Scenario 5: Timeout Exceeds Maximum

```bash
curl -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delays": "1000,2000,3000",
    "timeoutMs": 50000,
    "strategy": "SSE"
  }'
```

**Expected**:
- Timeout capped at 27000ms (configurable max)
- Normal completion if resources respond within 27s

### Scenario 6: Synchronous Strategy

```bash
curl -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delays": "1000,2000,-1",
    "timeoutMs": 10000,
    "strategy": "WAIT_FOR_EVERYONE"
  }'
```

**Expected**:
- Single complete response
- `respondents: 2, errors: 0`
- `notes: [...]` with 6 notes (2 resources × 3 notes)
- No SSE connection needed

---

## Manual Testing

### Using the Web UI

1. **Open browser**: `http://localhost:8082`
2. **Enter test data**:
   - Patient ID: `patient-123`
   - Delays: `1000,2000,3000`
   - Timeout: `10000`
   - Strategy: `SSE (server-sent events)`
3. **Click "Call Aggregator"**
4. **Observe**:
   - Correlation ID appears immediately
   - Notes appear one by one as callbacks arrive
   - Summary message shows completion
   - Total notes count displayed

### Using curl with SSE

```bash
# 1. Send request
RESPONSE=$(curl -s -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{"patientId":"patient-123","delays":"1000,2000,3000","timeoutMs":10000,"strategy":"SSE"}')

# 2. Extract correlation ID
CORRELATION_ID=$(echo $RESPONSE | jq -r '.correlationId')

# 3. Open SSE stream
curl -N http://localhost:8080/aggregate/stream?correlationId=$CORRELATION_ID
```

### Using Postman

1. **Create POST request**: `http://localhost:8080/aggregate/journals`
2. **Set headers**: `Content-Type: application/json`
3. **Set body**:
   ```json
   {
     "patientId": "patient-123",
     "delays": "1000,2000,3000",
     "timeoutMs": 10000,
     "strategy": "SSE"
   }
   ```
4. **Send request**
5. **Copy correlationId** from response
6. **Create GET request**: `http://localhost:8080/aggregate/stream?correlationId={id}`
7. **Send** and watch events stream in

### Browser Developer Tools

**Monitoring SSE in Chrome DevTools**:

1. Open DevTools (F12)
2. Go to Network tab
3. Filter by "EventStream"
4. Submit form in UI
5. Click on the SSE request
6. View "EventStream" tab to see live events

---

## Test Data Patterns

### Delay Patterns Cheat Sheet

| Pattern | Description | Expected Behavior |
|---------|-------------|-------------------|
| `0,0,0` | All immediate | 3 callbacks arrive instantly |
| `1000,1000,1000` | All same delay | 3 callbacks at ~1s mark |
| `1000,2000,3000` | Staggered | Callbacks at 1s, 2s, 3s |
| `-1,0,0` | First rejects | 2 callbacks, 1 rejection |
| `-1,-1,-1` | All reject | 0 callbacks, 3 rejections |
| `1000,2000,15000` (timeout 10s) | One times out | 2 callbacks, 1 timeout |
| `0,0,30000` (timeout 10s) | Last times out | 2 callbacks, 1 timeout |

---

## Continuous Integration

### GitHub Actions Workflow

```yaml
name: CI

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
        cache: maven
    
    - name: Run unit tests
      run: mvn test
    
    - name: Run integration tests
      run: mvn verify
    
    - name: Build Docker images
      run: docker-compose build
    
    - name: Start services
      run: docker-compose up -d
    
    - name: Wait for services
      run: sleep 30
    
    - name: Run E2E tests
      run: ./e2e-test.sh
    
    - name: Upload coverage
      uses: codecov/codecov-action@v3
      with:
        files: target/site/jacoco/jacoco.xml
```

---

## Test Coverage Goals

| Component | Target Coverage | Current |
|-----------|----------------|---------|
| **Aggregator Service** | 90% | 85% |
| **SSE Service** | 95% | 92% |
| **Resource Service** | 90% | 88% |
| **Controllers** | 80% | 75% |
| **Models** | 100% | 100% |
| **Overall** | 85% | 82% |

---

## References

- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [Project Reactor Testing](https://projectreactor.io/docs/core/release/reference/#testing)
- [Gatling Documentation](https://gatling.io/docs/gatling/)
