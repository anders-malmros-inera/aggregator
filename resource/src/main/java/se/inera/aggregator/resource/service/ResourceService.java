package se.inera.aggregator.resource.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import se.inera.aggregator.resource.model.JournalCallback;
import se.inera.aggregator.resource.model.JournalCommand;
import se.inera.aggregator.resource.model.JournalNote;

import java.time.Duration;
import java.util.List;

@Service
public class ResourceService {

    private static final Logger logger = LoggerFactory.getLogger(ResourceService.class);

    private final WebClient webClient;
    private final JournalNoteGenerator noteGenerator;
    
    @Value("${resource.id}")
    private String resourceId;

    public ResourceService(WebClient.Builder webClientBuilder, JournalNoteGenerator noteGenerator) {
        this.webClient = webClientBuilder.build();
        this.noteGenerator = noteGenerator;
    }

    public Mono<Void> processJournalRequest(JournalCommand command) {
        logger.info("Received journal request for patient {} with delay {} ms", command.getPatientId(), command.getDelay());
        
        if (command.getDelay() < 0) {
            return Mono.error(new IllegalArgumentException("Request rejected - delay is negative"));
        }

        // Start async processing in the background
        Mono.delay(Duration.ofMillis(command.getDelay()))
            .then(Mono.defer(() -> sendCallback(command)))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                v -> logger.info("Callback sent successfully for correlation ID {}", command.getCorrelationId()),
                error -> logger.error("Error sending callback for correlation ID {}: {}", command.getCorrelationId(), error.getMessage())
            );

        // Return immediately
        return Mono.empty();
    }

    private Mono<Void> sendCallback(JournalCommand command) {
        logger.info("Preparing callback for patient {} to {}", command.getPatientId(), command.getCallbackUrl());
        
        List<JournalNote> notes = noteGenerator.generateSampleNotes(command.getPatientId(), resourceId);
        
        JournalCallback callback = new JournalCallback(
            resourceId,
            command.getPatientId(),
            command.getCorrelationId(),
            command.getDelay(),
            "ok",
            notes
        );

        logger.info("Sending callback with {} notes to {}", notes.size(), command.getCallbackUrl());

        return webClient.post()
            .uri(command.getCallbackUrl())
            .bodyValue(callback)
            .retrieve()
            .toBodilessEntity()
            .doOnSuccess(v -> logger.info("Callback posted successfully"))
            .doOnError(error -> logger.error("Error posting callback: {}", error.getMessage()))
            .then();
    }
    
    public Mono<JournalCallback> processJournalRequestSynchronously(String patientId, int delay) {
        logger.info("Processing synchronous journal request for patient {} with delay {} ms", patientId, delay);
        
        if (delay < 0) {
            return Mono.just(new JournalCallback(
                resourceId,
                patientId,
                null,
                null,
                "REJECTED",
                null
            ));
        }

        return Mono.delay(Duration.ofMillis(delay))
            .then(Mono.defer(() -> {
                List<JournalNote> notes = noteGenerator.generateSampleNotes(patientId, resourceId);
                logger.info("Generated {} notes for patient {}", notes.size(), patientId);
                
                return Mono.just(new JournalCallback(
                    resourceId,
                    patientId,
                    null,
                    delay,
                    "ok",
                    notes
                ));
            }));
    }
    
    public String getResourceId() {
        return resourceId;
    }
}

