package se.inera.aggregator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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

    public AggregatorService(WebClient.Builder webClientBuilder, SseService sseService) {
        this.webClient = webClientBuilder.build();
        this.sseService = sseService;
    }

    public Mono<JournalResponse> aggregateJournals(JournalRequest request) {
        String correlationId = UUID.randomUUID().toString();
        String[] delayStrings = parseDelays(request.getDelays());
        
        logger.info("Starting aggregation for patient {} with correlation ID {}", request.getPatientId(), correlationId);
        logger.info("Resource URLs: {}", resourceUrls);
        
        String[] resourceUrlArray = resourceUrls.split(",");
    List<Mono<Boolean>> resourceCalls = new ArrayList<>();
        
        for (int i = 0; i < 3; i++) {
            int delay = parseDelay(i < delayStrings.length ? delayStrings[i] : "0");
            String resourceUrl = i < resourceUrlArray.length ? resourceUrlArray[i].trim() : resourceUrlArray[0].trim();
            
            logger.info("Calling resource {} with delay {}", resourceUrl, delay);
            
            JournalCommand command = new JournalCommand(
                request.getPatientId(),
                delay,
                callbackUrl,
                correlationId
            );
            
            Mono<Boolean> call = callResource(resourceUrl, command);
            resourceCalls.add(call);
        }
        // Register how many callbacks we expect for this correlationId (one per resource call)
        sseService.registerExpected(correlationId, resourceCalls.size());
        
        return Flux.merge(resourceCalls)
            .filter(accepted -> accepted)
            .count()
            .map(respondents -> {
                logger.info("Aggregation complete: {} respondents", respondents);
                return new JournalResponse(respondents.intValue(), correlationId);
            });
    }

    private Mono<Boolean> callResource(String resourceUrl, JournalCommand command) {
        return webClient.post()
            .uri(resourceUrl + "/journals")
            .bodyValue(command)
            .retrieve()
            .toBodilessEntity()
            .map(response -> {
                boolean accepted = response.getStatusCode() == HttpStatus.OK;
                logger.info("Resource {} returned status {} (accepted={})", resourceUrl, response.getStatusCode(), accepted);
                return accepted;
            })
            .doOnError(error -> logger.error("Error calling resource {}: {}", resourceUrl, error.getMessage(), error))
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
