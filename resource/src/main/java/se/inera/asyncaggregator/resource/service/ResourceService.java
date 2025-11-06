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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ResourceService {

    private static final Logger logger = LoggerFactory.getLogger(ResourceService.class);

    private final WebClient webClient;
    
    @Value("${resource.id}")
    private String resourceId;

    public ResourceService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
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
        
        List<JournalNote> notes = generateSampleNotes(command.getPatientId());
        
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

    private List<JournalNote> generateSampleNotes(String patientId) {
        List<JournalNote> notes = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
        
        for (int i = 0; i < 2; i++) {
            String noteId = UUID.randomUUID().toString();
            String date = randomDateInPastYear().format(formatter);
            
            JournalNote note = new JournalNote(
                noteId,
                date,
                "Caregiver-" + resourceId,
                patientId,
                "Doctor-" + (i + 1),
                "Sample note " + (i + 1) + " from " + resourceId
            );
            notes.add(note);
        }
        
        return notes;
    }

    private LocalDateTime randomDateInPastYear() {
        // Return a random LocalDateTime within the past 365 days (inclusive)
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusDays(365);
        long secondsBetween = ChronoUnit.SECONDS.between(start, now);
        long randomOffsetSeconds = ThreadLocalRandom.current().nextLong(secondsBetween + 1);
        return start.plusSeconds(randomOffsetSeconds);
    }
}
