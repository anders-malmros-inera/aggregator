package se.inera.aggregator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import se.inera.aggregator.model.JournalCallback;
import se.inera.aggregator.service.sse.SinkInfo;
import se.inera.aggregator.model.JournalCommand;
import se.inera.aggregator.model.JournalRequest;
import se.inera.aggregator.model.JournalResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AggregatorService {

    private static final Logger logger = LoggerFactory.getLogger(AggregatorService.class);

    private final WebClient webClient;
    private final SseService sseService;
    
    @Value("${aggregator.callback.url}")
    private String callbackUrl;
    
    @Value("${resource.urls}")
    private String resourceUrls;

    @Value("${aggregator.timeout.max-ms:27000}")
    private Long maxTimeoutMs;

    private static final int DEFAULT_RESOURCE_COUNT = 3;
    public AggregatorService(WebClient.Builder webClientBuilder, SseService sseService) {
        this.webClient = webClientBuilder.build();
        this.sseService = sseService;
    }

    public Mono<JournalResponse> aggregateJournals(JournalRequest request) {
        String correlationId = UUID.randomUUID().toString();
        String[] delayStrings = parseDelays(request.getDelays());
        
        // Apply maximum timeout constraint
        Long requestedTimeout = request.getTimeoutMs();
        Long timeoutMs = (requestedTimeout != null && requestedTimeout <= maxTimeoutMs) 
            ? requestedTimeout 
            : maxTimeoutMs;
        
        if (requestedTimeout != null && requestedTimeout > maxTimeoutMs) {
            logger.warn("Client requested timeout {}ms exceeds maximum {}ms, using maximum", 
                requestedTimeout, maxTimeoutMs);
        }

        logger.info("Starting aggregation for patient {} with correlation ID {} and timeout {}ms", 
            request.getPatientId(), correlationId, timeoutMs);
        logger.info("Resource URLs: {}", resourceUrls);

        List<Mono<Boolean>> resourceCalls = buildResourceCalls(request, correlationId, delayStrings, timeoutMs);

        // Register how many resource callbacks we expect for this correlationId.
        // We must return immediately so the client can open the SSE stream
        // before callbacks arrive. Resource calls are started asynchronously
        // and will drive the SSE emission via /callback endpoints.
        // Rejected resources will emit REJECTED events immediately (no async callback).
        sseService.registerExpected(correlationId, resourceCalls.size());
        
        // Schedule timeout for waiting for all callbacks
        sseService.scheduleTimeout(correlationId, timeoutMs);

        // Start resource calls asynchronously; do not wait here.
        Disposable disposable = Flux.merge(resourceCalls)
            .doOnNext(accepted -> logger.debug("Resource response received: accepted={}", accepted))
            .doOnError(err -> logger.warn("Error during resource calls for {}: {}", correlationId, err.getMessage()))
            .subscribe();

        // Store disposable so we can cancel if client disconnects
        SinkInfo sinkInfo = sseService.getSinkInfo(correlationId);
        if (sinkInfo != null) {
            sinkInfo.setCancellationDisposable(disposable);
        }

        // Return immediately with 0 respondents (they will be reported via SSE callbacks).
        return Mono.just(new JournalResponse(0, correlationId));
    }

    private List<Mono<Boolean>> buildResourceCalls(JournalRequest request, String correlationId, String[] delayStrings, Long timeoutMs) {
        List<Mono<Boolean>> resourceCalls = new ArrayList<>();
        String[] resourceUrlArray = getResourceUrlArray();

        int calls = DEFAULT_RESOURCE_COUNT;
        for (int i = 0; i < calls; i++) {
            int delay = parseDelay(i < delayStrings.length ? delayStrings[i] : "0");
            String resourceUrl = selectResourceUrl(resourceUrlArray, i);

            logger.info("Calling resource {} with delay {}", resourceUrl, delay);

            JournalCommand command = createJournalCommand(request.getPatientId(), delay, correlationId);

            Mono<Boolean> call = callResource(resourceUrl, command, timeoutMs);
            resourceCalls.add(call);
        }
        return resourceCalls;
    }

    private String[] getResourceUrlArray() {
        if (resourceUrls == null || resourceUrls.trim().isEmpty()) {
            return new String[] {"http://localhost:8080"};
        }
        String[] arr = resourceUrls.split(",");
        for (int i = 0; i < arr.length; i++) {
            arr[i] = arr[i].trim();
        }
        return arr;
    }

    private String selectResourceUrl(String[] urls, int index) {
        if (urls == null || urls.length == 0) return "http://localhost:8080";
        return index < urls.length ? urls[index] : urls[0];
    }

    private JournalCommand createJournalCommand(String patientId, int delay, String correlationId) {
        return new JournalCommand(patientId, delay, callbackUrl, correlationId);
    }

    private Mono<Boolean> callResource(String resourceUrl, JournalCommand command, Long timeoutMs) {
        return webClient.post()
            .uri(resourceUrl + "/journals")
            .bodyValue(command)
            .retrieve()
            .toBodilessEntity()
            .timeout(java.time.Duration.ofMillis(timeoutMs))
            .map(response -> {
                boolean accepted = response.getStatusCode() == HttpStatus.OK;
                logger.info("Resource {} returned status {} (accepted={})", resourceUrl, response.getStatusCode(), accepted);
                
                // If rejected, manually trigger completion check since resource won't callback
                if (!accepted) {
                    logger.info("Resource rejected request, manually counting response for correlation {}", command.getCorrelationId());
                    sseService.sendEvent(command.getCorrelationId(), 
                        new JournalCallback(
                            resourceUrl, 
                            command.getPatientId(), 
                            command.getCorrelationId(), 
                            null, 
                            "REJECTED", 
                            null,
                            0
                        )
                    );
                }
                
                return accepted;
            })
            .doOnError(error -> {
                String errorType;
                if (error instanceof java.util.concurrent.TimeoutException) {
                    errorType = "TIMEOUT";
                    logger.warn("Resource {} timed out after {}ms for correlation {}", 
                        resourceUrl, timeoutMs, command.getCorrelationId());
                } else if (error.getMessage() != null && error.getMessage().contains("Connection prematurely closed")) {
                    errorType = "CONNECTION_CLOSED";
                    logger.warn("Resource {} closed connection prematurely for correlation {}", 
                        resourceUrl, command.getCorrelationId());
                } else {
                    errorType = "ERROR";
                    logger.error("Error calling resource {}: {}", resourceUrl, error.getMessage(), error);
                }
                
                // On error/timeout/closed connection, count as a response to avoid hanging
                logger.info("Resource call failed ({}), manually counting response for correlation {}", 
                    errorType, command.getCorrelationId());
                sseService.sendEvent(command.getCorrelationId(), 
                    new JournalCallback(
                        resourceUrl, 
                        command.getPatientId(), 
                        command.getCorrelationId(), 
                        null, 
                        errorType, 
                        null,
                        0
                    )
                );
            })
            .onErrorReturn(false);
    }

    private String[] parseDelays(String delays) {
        if (delays == null || delays.trim().isEmpty()) {
            return new String[]{"0", "0", "0"};
        }
        return delays.split(",");
    }

    private int parseDelay(String delay) {
        try {
            return Integer.parseInt(delay.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
