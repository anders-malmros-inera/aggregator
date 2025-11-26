# Quick Reference Guide

## Common Commands

### Docker Commands

```bash
# Start all services
docker-compose up --build

# Start in background
docker-compose up -d

# View logs (all services)
docker-compose logs -f

# View logs (specific service)
docker-compose logs -f aggregator

# Stop all services
docker-compose down

# Stop and remove volumes
docker-compose down -v

# Restart a service
docker-compose restart aggregator

# Rebuild specific service
docker-compose build aggregator

# Execute command in container
docker-compose exec aggregator bash
```

### Maven Commands

```bash
# Build all modules
mvn clean package

# Run tests
mvn test

# Run integration tests
mvn verify

# Skip tests
mvn clean package -DskipTests

# Run specific service
cd aggregator && mvn spring-boot:run

# Build with coverage
mvn clean test jacoco:report
```

### Testing Commands

```bash
# Unit tests only
mvn test

# Integration tests only
mvn verify -DskipUnitTests

# Specific test class
mvn test -Dtest=AggregatorServiceTest

# Specific test method
mvn test -Dtest=AggregatorServiceTest#shouldGenerateCorrelationId

# Run E2E tests
./e2e-test.sh
```

---

## API Quick Reference

### Aggregator Endpoints

```bash
# Initiate SSE aggregation
curl -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delays": "1000,2000,3000",
    "timeoutMs": 10000,
    "strategy": "SSE"
  }'

# Initiate synchronous aggregation
curl -X POST http://localhost:8080/aggregate/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delays": "1000,2000,3000",
    "timeoutMs": 10000,
    "strategy": "WAIT_FOR_EVERYONE"
  }'

# Open SSE stream
curl -N http://localhost:8080/aggregate/stream?correlationId={uuid}

# Send callback (resources use this)
curl -X POST http://localhost:8080/aggregate/callback \
  -H "Content-Type: application/json" \
  -d '{
    "source": "resource-1",
    "patientId": "patient-123",
    "correlationId": "{uuid}",
    "delayMs": 1000,
    "status": "ok",
    "notes": []
  }'

# Health check
curl http://localhost:8080/actuator/health

# Metrics
curl http://localhost:8080/actuator/metrics
```

### Resource Endpoints

```bash
# Async processing (callback pattern)
curl -X POST http://localhost:8081/journals \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delay": 1000,
    "callbackUrl": "http://aggregator:8080/aggregate/callback",
    "correlationId": "{uuid}"
  }'

# Sync processing (direct response)
curl -X POST http://localhost:8081/journals/direct \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient-123",
    "delay": 1000
  }'

# Health check
curl http://localhost:8081/actuator/health
```

### Client Endpoints

```bash
# Open UI
http://localhost:8082

# Health check
curl http://localhost:8082/actuator/health
```

---

## Configuration Quick Reference

### Environment Variables

```bash
# Aggregator
SPRING_PROFILES_ACTIVE=production
AGGREGATOR_TIMEOUT_MAX_MS=27000
AGGREGATOR_CALLBACK_URL=http://aggregator:8080/aggregate/callback
RESOURCE_URLS=http://resource1:8080,http://resource2:8080,http://resource3:8080
SERVER_PORT=8080

# Resource
RESOURCE_ID=resource-1
SERVER_PORT=8081

# Client
SERVER_PORT=8082
AGGREGATOR_URL=http://aggregator:8080

# JVM
JAVA_OPTS=-Xms512m -Xmx1g -XX:+UseG1GC
```

### Application Properties

```yaml
# aggregator/src/main/resources/application.yml
server:
  port: 8080

aggregator:
  timeout:
    max-ms: 27000
    default-ms: 10000
  callback:
    url: http://aggregator:8080/aggregate/callback

resource:
  urls: http://resource1:8080,http://resource2:8080,http://resource3:8080

logging:
  level:
    se.inera.aggregator: DEBUG
```

---

## Troubleshooting Quick Reference

### Service Won't Start

```bash
# Check if port is already in use
netstat -ano | findstr :8080

# Check Docker containers
docker ps -a

# Check logs
docker-compose logs aggregator

# Remove and restart
docker-compose down -v
docker-compose up --build
```

### SSE Not Working

```bash
# Test SSE endpoint directly
curl -N http://localhost:8080/aggregate/stream?correlationId=test

# Check CORS headers
curl -I http://localhost:8080/aggregate/stream

# Verify sink exists
docker-compose logs aggregator | grep "sink"
```

### Callbacks Not Arriving

```bash
# Check callback URL from resource perspective
docker-compose exec resource1 curl http://aggregator:8080/actuator/health

# Check network connectivity
docker network inspect aggregator_aggregator-network

# Test callback endpoint
curl -X POST http://localhost:8080/aggregate/callback \
  -H "Content-Type: application/json" \
  -d '{"source":"test","correlationId":"test","status":"ok","notes":[]}'
```

### High Timeout Rate

```bash
# Check current timeout setting
curl http://localhost:8080/actuator/configprops | jq '.aggregator'

# Check resource response times
docker-compose logs resource1 | grep "Processing"

# Increase timeout
docker-compose down
# Edit docker-compose.yml: AGGREGATOR_TIMEOUT_MAX_MS=50000
docker-compose up -d
```

### Memory Issues

```bash
# Check memory usage
docker stats aggregator

# Increase memory limit
docker-compose down
# Edit docker-compose.yml: deploy.resources.limits.memory: 2g
docker-compose up -d

# Generate heap dump
docker exec aggregator jmap -dump:live,format=b,file=/tmp/heap.bin 1
docker cp aggregator:/tmp/heap.bin ./heap.bin
```

---

## Test Scenarios Cheat Sheet

### Delay Values

| Delays | Behavior |
|--------|----------|
| `1000,2000,3000` | All succeed with staggered timing |
| `0,0,0` | All succeed immediately |
| `-1,0,0` | First rejects (401), others succeed |
| `-1,-1,-1` | All reject (401) |
| `1000,2000,15000` (timeout 10s) | Third times out |

### Expected Results

| Scenario | Respondents | Errors | Notes Count |
|----------|-------------|--------|-------------|
| All succeed (3) | 3 | 0 | 9 |
| One rejects | 2 | 0 | 6 |
| One times out | 2 | 1 | 6 |
| All reject | 0 | 0 | 0 |
| All timeout | 0 | 3 | 0 |

### Strategies

| Strategy | Response Type | SSE Connection | Use Case |
|----------|---------------|----------------|----------|
| `SSE` | `JournalResponse` | Yes | Real-time updates, long operations |
| `WAIT_FOR_EVERYONE` | `AggregatedJournalResponse` | No | Simple clients, batch processing |

---

## Code Snippets

### Adding a New Event Type

```java
// 1. Add event type to status enum
public class JournalCallback {
    public enum Status {
        OK, REJECTED, TIMEOUT, CONNECTION_CLOSED, ERROR, NEW_TYPE
    }
}

// 2. Handle in SseService
public void sendEvent(JournalCallback callback) {
    String status = callback.getStatus();
    if ("NEW_TYPE".equals(status)) {
        // Handle new type
        sinkInfo.getErrors().incrementAndGet();
    }
}

// 3. Update tests
@Test
void shouldHandleNewType() {
    // Test implementation
}
```

### Adding a Custom Metric

```java
// 1. Add MeterRegistry dependency
@Autowired
private MeterRegistry meterRegistry;

// 2. Increment counter
meterRegistry.counter("aggregator.custom.metric", "type", "value").increment();

// 3. Record gauge
meterRegistry.gauge("aggregator.active.sinks", sinkManager.getSinkCount());

// 4. Record timer
Timer.Sample sample = Timer.start(meterRegistry);
// ... do work ...
sample.stop(meterRegistry.timer("aggregator.operation.duration", "operation", "aggregate"));
```

### Adding Request Validation

```java
@PostMapping("/journals")
public Mono<?> aggregateJournals(@Valid @RequestBody JournalRequest request) {
    // Already validated by @Valid
}

// Add to model
public class JournalRequest {
    @NotBlank(message = "Patient ID is required")
    private String patientId;
    
    @Min(value = 1000, message = "Timeout must be at least 1000ms")
    @Max(value = 60000, message = "Timeout must not exceed 60000ms")
    private Integer timeoutMs;
}
```

---

## Performance Optimization Tips

1. **Increase parallelism**: Tune WebClient connection pool

   ```yaml
   reactor.netty.connection-provider.max-connections: 500
   ```

2. **Adjust JVM heap**: Increase memory for high load

   ```bash
   JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC"
   ```

3. **Enable compression**: Reduce SSE bandwidth

   ```yaml
   server.compression.enabled: true
   ```

4. **Use caching**: Cache resource responses (future)

   ```java
   @Cacheable("notes")
   public List<JournalNote> generateNotes(String patientId) { }
   ```

5. **Implement circuit breaker**: Fail fast for down resources

   ```java
   @CircuitBreaker(name = "resourceService")
   public Mono<Callback> callResource(...) { }
   ```

---

## Useful Links

### Documentation

- [Main README](./README.md)
- [Architecture Decisions](./ARCHITECTURE.md)
- [State Diagrams](./STATES-AND-ERRORS.md)
- [Deployment Guide](./DEPLOYMENT.md)
- [Testing Guide](./TESTING.md)

### Technologies

- [Spring WebFlux](https://docs.spring.io/spring-framework/reference/web/webflux.html)
- [Project Reactor](https://projectreactor.io/docs/core/release/reference/)
- [SSE Standard](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [Docker Compose](https://docs.docker.com/compose/)

### Tools

- [Postman](https://www.postman.com/)
- [curl](https://curl.se/)
- [jq](https://stedolan.github.io/jq/) - JSON processor
- [Docker Desktop](https://www.docker.com/products/docker-desktop/)

---

## Quick Debugging Checklist

- [ ] Services running? `docker-compose ps`
- [ ] Logs clean? `docker-compose logs | grep ERROR`
- [ ] Health checks passing? `curl http://localhost:8080/actuator/health`
- [ ] Network accessible? `docker network inspect aggregator_aggregator-network`
- [ ] Ports available? `netstat -ano | findstr :8080`
- [ ] Environment vars set? `docker-compose exec aggregator env | grep AGGREGATOR`
- [ ] Resources responding? `curl http://localhost:8081/actuator/health`
- [ ] Timeouts reasonable? Check `AGGREGATOR_TIMEOUT_MAX_MS`
- [ ] Memory sufficient? `docker stats`
- [ ] Tests passing? `mvn test`

---

## Contact Information

- **Project Repository**: [GitHub](https://github.com/your-org/aggregator)
- **Issue Tracker**: [GitHub Issues](https://github.com/your-org/aggregator/issues)
- **Team**: [dev-team@example.com](mailto:dev-team@example.com)
- **Documentation**: This repository's `/docs` folder
