# Deployment and Operations Guide

## Overview

This guide covers deployment strategies, configuration management, monitoring, troubleshooting, and operational best practices for the aggregator system.

---

## Table of Contents

1. [Deployment Options](#deployment-options)
2. [Configuration](#configuration)
3. [Environment Management](#environment-management)
4. [Monitoring and Observability](#monitoring-and-observability)
5. [Troubleshooting](#troubleshooting)
6. [Performance Tuning](#performance-tuning)
7. [Security Considerations](#security-considerations)
8. [Backup and Recovery](#backup-and-recovery)

---

## Deployment Options

### 1. Docker Compose (Development & Demo)

**Current Implementation** - Best for local development and demos.

```bash
# Build and start all services
docker-compose up --build

# Start in detached mode
docker-compose up -d

# View logs
docker-compose logs -f aggregator

# Stop services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

**Service Ports:**
- Aggregator: 8080
- Client: 8082
- Resource-1: 8081
- Resource-2: 8083
- Resource-3: 8084

### 2. Kubernetes Deployment (Production)

**Recommended for production environments.**

#### Deployment Manifests

```yaml
# aggregator-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: aggregator
  labels:
    app: aggregator
spec:
  replicas: 3
  selector:
    matchLabels:
      app: aggregator
  template:
    metadata:
      labels:
        app: aggregator
    spec:
      containers:
      - name: aggregator
        image: aggregator:latest
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "production"
        - name: AGGREGATOR_TIMEOUT_MAX_MS
          value: "27000"
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: aggregator-service
spec:
  selector:
    app: aggregator
  ports:
  - protocol: TCP
    port: 8080
    targetPort: 8080
  type: LoadBalancer
```

#### ConfigMap for Environment-Specific Settings

```yaml
# aggregator-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: aggregator-config
data:
  application.yml: |
    aggregator:
      timeout:
        max-ms: 27000
      callback:
        url: http://aggregator-service:8080/aggregate/callback
    resource:
      urls: http://resource-1:8080,http://resource-2:8080,http://resource-3:8080
    spring:
      profiles:
        active: production
    logging:
      level:
        se.inera.aggregator: INFO
```

#### Deploy to Kubernetes

```bash
# Apply configurations
kubectl apply -f aggregator-configmap.yaml
kubectl apply -f aggregator-deployment.yaml

# Check deployment status
kubectl get deployments
kubectl get pods
kubectl get services

# View logs
kubectl logs -f deployment/aggregator

# Scale deployment
kubectl scale deployment/aggregator --replicas=5
```

### 3. Cloud Platform Deployment

#### AWS ECS/Fargate

```json
{
  "family": "aggregator-task",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "containerDefinitions": [
    {
      "name": "aggregator",
      "image": "your-registry/aggregator:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "SPRING_PROFILES_ACTIVE",
          "value": "production"
        },
        {
          "name": "AGGREGATOR_TIMEOUT_MAX_MS",
          "value": "27000"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/aggregator",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

#### Azure Container Instances

```bash
az container create \
  --resource-group aggregator-rg \
  --name aggregator \
  --image your-registry/aggregator:latest \
  --cpu 2 \
  --memory 4 \
  --ports 8080 \
  --environment-variables \
    SPRING_PROFILES_ACTIVE=production \
    AGGREGATOR_TIMEOUT_MAX_MS=27000 \
  --restart-policy Always
```

---

## Configuration

### Application Properties

#### Development (`application.yml`)

```yaml
server:
  port: 8080

aggregator:
  timeout:
    max-ms: 27000  # Maximum timeout (27 seconds)
  callback:
    url: http://aggregator:8080/aggregate/callback

resource:
  urls: http://resource1:8080,http://resource2:8080,http://resource3:8080

spring:
  application:
    name: aggregator
  webflux:
    base-path: /
  
logging:
  level:
    root: INFO
    se.inera.aggregator: DEBUG
```

#### Production (`application-production.yml`)

```yaml
server:
  port: 8080
  shutdown: graceful  # Graceful shutdown

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # Wait up to 30s for shutdown

aggregator:
  timeout:
    max-ms: 27000
    default-ms: 10000
  callback:
    url: ${AGGREGATOR_CALLBACK_URL:http://aggregator-service:8080/aggregate/callback}

resource:
  urls: ${RESOURCE_URLS}  # Injected from environment
  connect-timeout-ms: 5000
  read-timeout-ms: 30000

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
  metrics:
    export:
      prometheus:
        enabled: true

logging:
  level:
    root: WARN
    se.inera.aggregator: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %logger{36} - %msg%n"
```

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `default` | No |
| `SERVER_PORT` | Server port | `8080` | No |
| `AGGREGATOR_TIMEOUT_MAX_MS` | Maximum timeout (ms) | `27000` | No |
| `AGGREGATOR_CALLBACK_URL` | Callback URL | Auto-detected | No |
| `RESOURCE_URLS` | Comma-separated resource URLs | Required for prod | Yes (prod) |
| `RESOURCE_ID` | Resource identifier (resources only) | N/A | Yes (resources) |
| `JAVA_OPTS` | JVM options | `-Xmx512m` | No |

### JVM Tuning

```bash
# Development
JAVA_OPTS="-Xms256m -Xmx512m"

# Production
JAVA_OPTS="-Xms1g -Xmx2g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/var/log/heapdump.hprof \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=9010 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false"
```

---

## Environment Management

### Development Environment

```bash
# Clone repository
git clone https://github.com/your-org/aggregator.git
cd aggregator

# Build all modules
mvn clean package

# Run with Docker Compose
docker-compose up --build
```

### Staging Environment

```bash
# Build production images
docker build -t aggregator:staging -f aggregator/Dockerfile .
docker build -t resource:staging -f resource/Dockerfile .
docker build -t client:staging -f client/Dockerfile .

# Push to registry
docker tag aggregator:staging your-registry/aggregator:staging
docker push your-registry/aggregator:staging

# Deploy to staging cluster
kubectl config use-context staging
kubectl apply -f k8s/staging/
```

### Production Environment

```bash
# Build production images with version tag
VERSION=$(git describe --tags --always)
docker build -t aggregator:${VERSION} -f aggregator/Dockerfile .

# Push to production registry
docker tag aggregator:${VERSION} your-registry/aggregator:${VERSION}
docker tag aggregator:${VERSION} your-registry/aggregator:latest
docker push your-registry/aggregator:${VERSION}
docker push your-registry/aggregator:latest

# Deploy with zero-downtime
kubectl config use-context production
kubectl set image deployment/aggregator aggregator=your-registry/aggregator:${VERSION}
kubectl rollout status deployment/aggregator
```

---

## Monitoring and Observability

### Health Checks

The aggregator exposes Spring Boot Actuator endpoints:

```bash
# Liveness probe (Kubernetes)
curl http://localhost:8080/actuator/health/liveness

# Readiness probe (Kubernetes)
curl http://localhost:8080/actuator/health/readiness

# Detailed health
curl http://localhost:8080/actuator/health
```

### Metrics Collection

#### Prometheus Integration

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'aggregator'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['aggregator:8080']
```

#### Key Metrics

```promql
# Request rate
rate(http_server_requests_seconds_count{job="aggregator"}[5m])

# Average latency
rate(http_server_requests_seconds_sum{job="aggregator"}[5m]) 
/ rate(http_server_requests_seconds_count{job="aggregator"}[5m])

# Error rate
rate(http_server_requests_seconds_count{status=~"5.."}[5m])

# Active SSE connections (custom metric)
sse_active_connections{job="aggregator"}

# Timeout rate (custom metric)
rate(aggregator_timeouts_total[5m])
```

### Logging

#### Structured Logging (Production)

```java
@Slf4j
public class AggregatorService {
    public Mono<JournalResponse> aggregateJournals(JournalRequest request) {
        log.info("Aggregation request received", 
            kv("patientId", request.getPatientId()),
            kv("strategy", request.getStrategy()),
            kv("timeoutMs", request.getTimeoutMs()));
        // ...
    }
}
```

#### Log Aggregation

**ELK Stack:**
```yaml
# filebeat.yml
filebeat.inputs:
  - type: container
    paths:
      - '/var/lib/docker/containers/*/*.log'
    processors:
      - add_kubernetes_metadata:
          host: ${NODE_NAME}
          matchers:
          - logs_path:
              logs_path: "/var/lib/docker/containers/"

output.elasticsearch:
  hosts: ["elasticsearch:9200"]
```

**CloudWatch (AWS):**
```json
{
  "logConfiguration": {
    "logDriver": "awslogs",
    "options": {
      "awslogs-group": "/ecs/aggregator",
      "awslogs-region": "us-east-1",
      "awslogs-stream-prefix": "ecs"
    }
  }
}
```

### Distributed Tracing

#### Spring Cloud Sleuth + Zipkin

```yaml
# application.yml
spring:
  sleuth:
    sampler:
      probability: 0.1  # 10% sampling
  zipkin:
    base-url: http://zipkin:9411
```

### Alerting

#### Prometheus Alerts

```yaml
# alerts.yml
groups:
  - name: aggregator_alerts
    rules:
      - alert: HighErrorRate
        expr: rate(http_server_requests_seconds_count{status=~"5..",job="aggregator"}[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High error rate on aggregator"
          description: "Error rate is {{ $value }} req/s"
      
      - alert: HighTimeoutRate
        expr: rate(aggregator_timeouts_total[5m]) > 0.5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "High timeout rate"
          description: "Timeout rate is {{ $value }} timeouts/s"
      
      - alert: SlowResponseTime
        expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{job="aggregator"}[5m])) > 30
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Slow response times (p95 > 30s)"
```

---

## Troubleshooting

### Common Issues

#### 1. SSE Connection Not Opening

**Symptoms:**
- Client receives correlationId but no SSE events
- Browser console shows EventSource errors

**Diagnosis:**
```bash
# Check if aggregator is running
curl http://localhost:8080/actuator/health

# Test SSE endpoint directly
curl -N http://localhost:8080/aggregate/stream?correlationId=test-id

# Check aggregator logs
docker-compose logs aggregator | grep "SSE"
```

**Solutions:**
- Ensure correlationId exists in sink manager
- Check CORS configuration
- Verify network connectivity
- Check if sink was prematurely removed

#### 2. Callbacks Not Arriving

**Symptoms:**
- SSE connection open but no callback events
- Timeout occurs even though resources are up

**Diagnosis:**
```bash
# Check resource health
curl http://localhost:8081/actuator/health

# Test resource directly
curl -X POST http://localhost:8081/journals/direct \
  -H "Content-Type: application/json" \
  -d '{"patientId":"test","delay":0}'

# Check callback URL configuration
docker-compose exec aggregator env | grep CALLBACK
```

**Solutions:**
- Verify callback URL is accessible from resources
- Check Docker network configuration
- Ensure resources can resolve aggregator hostname
- Review resource logs for errors

#### 3. High Timeout Rate

**Symptoms:**
- Many requests completing with errors=N
- Clients reporting incomplete results

**Diagnosis:**
```bash
# Check current timeout configuration
curl http://localhost:8080/actuator/configprops | jq '.aggregator.timeout'

# Monitor resource response times
curl http://localhost:8081/actuator/metrics/http.server.requests

# Check resource logs
docker-compose logs resource1 | grep "Processing"
```

**Solutions:**
- Increase timeout value: `AGGREGATOR_TIMEOUT_MAX_MS=50000`
- Optimize resource processing
- Add resource caching
- Implement circuit breaker

#### 4. Memory Issues

**Symptoms:**
- OutOfMemoryError in logs
- Slow response times
- Container restarts

**Diagnosis:**
```bash
# Check memory usage
docker stats aggregator

# Generate heap dump
docker exec aggregator jmap -dump:live,format=b,file=/tmp/heap.bin 1

# Analyze with VisualVM or Eclipse MAT
```

**Solutions:**
- Increase container memory: `--memory=2g`
- Tune JVM: `JAVA_OPTS="-Xmx2g -XX:+UseG1GC"`
- Check for sink leaks (not removed after completion)
- Implement sink cleanup job

#### 5. Client Disconnects Not Detected

**Symptoms:**
- Sinks remain after client closes
- Growing memory usage
- Late callbacks processing unnecessarily

**Diagnosis:**
```bash
# Check active sinks (custom metric)
curl http://localhost:8080/actuator/metrics/sse.active.sinks

# Review doOnCancel logs
docker-compose logs aggregator | grep "cancel"
```

**Solutions:**
- Verify `doOnCancel()` handler registered
- Add periodic sink cleanup job
- Implement sink timeout (remove if idle > 5min)

### Debug Mode

Enable detailed logging:

```yaml
# application-debug.yml
logging:
  level:
    root: INFO
    se.inera.aggregator: DEBUG
    reactor.netty: DEBUG
    org.springframework.web: DEBUG
```

```bash
# Run with debug profile
docker-compose up -e SPRING_PROFILES_ACTIVE=debug
```

### Thread Dump Analysis

```bash
# Generate thread dump
docker exec aggregator jstack 1 > threaddump.txt

# Look for blocked threads
grep -A 10 "BLOCKED" threaddump.txt

# Look for reactor threads
grep -A 5 "reactor-http" threaddump.txt
```

---

## Performance Tuning

### Reactor Configuration

```java
@Configuration
public class ReactorConfig {
    @Bean
    public Scheduler boundedElastic() {
        return Schedulers.newBoundedElastic(
            10,        // Thread cap
            100,       // Queue size
            "aggregator-pool"
        );
    }
}
```

### WebClient Tuning

```java
@Bean
public WebClient webClient() {
    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(
            HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(30))
                .doOnConnected(conn -> 
                    conn.addHandlerLast(new ReadTimeoutHandler(30))
                        .addHandlerLast(new WriteTimeoutHandler(30))
                )
        ))
        .build();
}
```

### Connection Pooling

```yaml
# application.yml
spring:
  webflux:
    base-path: /
reactor:
  netty:
    connection-provider:
      max-connections: 500
      max-idle-time: 20s
      max-life-time: 60s
```

### Database Connection Pool (Future)

```yaml
spring:
  r2dbc:
    pool:
      initial-size: 10
      max-size: 50
      max-idle-time: 30m
      max-acquire-time: 3s
```

---

## Security Considerations

### Network Security

```yaml
# Use internal networks for backend communication
networks:
  frontend:
    driver: bridge
  backend:
    driver: bridge
    internal: true  # No external access

services:
  aggregator:
    networks:
      - frontend
      - backend
  
  resource1:
    networks:
      - backend  # Not exposed externally
```

### HTTPS/TLS

```yaml
server:
  ssl:
    enabled: true
    key-store: classpath:keystore.p12
    key-store-password: ${KEYSTORE_PASSWORD}
    key-store-type: PKCS12
    key-alias: aggregator
```

### Authentication & Authorization (Future)

```java
@Configuration
public class SecurityConfig {
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .authorizeExchange()
                .pathMatchers("/actuator/**").hasRole("ADMIN")
                .pathMatchers("/aggregate/**").authenticated()
                .anyExchange().permitAll()
            .and()
            .oauth2ResourceServer()
                .jwt()
            .and()
            .build();
    }
}
```

### Rate Limiting

```java
@Bean
public RateLimiter rateLimiter() {
    return RateLimiter.create(100.0);  // 100 requests/second
}

@Bean
public WebFilter rateLimitingFilter(RateLimiter rateLimiter) {
    return (exchange, chain) -> {
        if (!rateLimiter.tryAcquire()) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    };
}
```

---

## Backup and Recovery

### State Management

**Current**: No persistence, all state in-memory.

**Future Considerations**:

1. **Correlation ID Persistence**
   ```sql
   CREATE TABLE aggregation_sessions (
       correlation_id UUID PRIMARY KEY,
       patient_id VARCHAR(255),
       created_at TIMESTAMP,
       status VARCHAR(50),
       expected_callbacks INT,
       received_callbacks INT
   );
   ```

2. **Callback History**
   ```sql
   CREATE TABLE callbacks (
       id SERIAL PRIMARY KEY,
       correlation_id UUID REFERENCES aggregation_sessions,
       source VARCHAR(255),
       received_at TIMESTAMP,
       status VARCHAR(50),
       notes_count INT
   );
   ```

### Disaster Recovery

1. **Service Restart**
   - No data loss (in-memory only)
   - Active requests fail gracefully
   - Clients retry manually

2. **Kubernetes Pod Restart**
   ```yaml
   strategy:
     type: RollingUpdate
     rollingUpdate:
       maxUnavailable: 1
       maxSurge: 1
   ```

3. **Database Backup** (Future)
   ```bash
   # PostgreSQL backup
   pg_dump -h localhost -U aggregator -d aggregator_db > backup.sql
   
   # Restore
   psql -h localhost -U aggregator -d aggregator_db < backup.sql
   ```

---

## Operational Procedures

### Routine Maintenance

```bash
# Weekly: Check logs for errors
docker-compose logs --since 7d | grep ERROR

# Monthly: Review metrics
curl http://localhost:8080/actuator/metrics

# Quarterly: Performance testing
mvn gatling:test -Dgatling.simulationClass=LoadTest
```

### Scaling

```bash
# Horizontal scaling (Kubernetes)
kubectl scale deployment/aggregator --replicas=10

# Auto-scaling
kubectl autoscale deployment/aggregator --min=3 --max=10 --cpu-percent=70
```

### Rolling Updates

```bash
# Update image
kubectl set image deployment/aggregator aggregator=new-image:v2

# Monitor rollout
kubectl rollout status deployment/aggregator

# Rollback if needed
kubectl rollout undo deployment/aggregator
```

---

## Contact and Support

- **Documentation**: [README.md](./README.md)
- **Architecture**: [ARCHITECTURE.md](./ARCHITECTURE.md)
- **State Diagrams**: [STATES-AND-ERRORS.md](./STATES-AND-ERRORS.md)
- **Issues**: GitHub Issues
- **Team**: dev-team@example.com
