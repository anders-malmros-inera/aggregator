package se.inera.aggregator.controller;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import se.inera.aggregator.model.JournalCallback;
import se.inera.aggregator.model.JournalRequest;
import se.inera.aggregator.model.JournalResponse;
import se.inera.aggregator.service.AggregatorService;
import se.inera.aggregator.service.SseService;

@RestController
@RequestMapping("/aggregate")
@CrossOrigin(origins = "*")
public class AggregatorController {

    private final AggregatorService aggregatorService;
    private final SseService sseService;

    public AggregatorController(AggregatorService aggregatorService, SseService sseService) {
        this.aggregatorService = aggregatorService;
        this.sseService = sseService;
    }

    @PostMapping("/journals")
    public Mono<JournalResponse> aggregateJournals(@RequestBody JournalRequest request) {
        return aggregatorService.aggregateJournals(request);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<JournalCallback>> streamEvents(@RequestParam("correlationId") String correlationId) {
        return sseService.subscribe(correlationId)
            .map(callback -> ServerSentEvent.<JournalCallback>builder()
                .data(callback)
                .build());
    }

    @PostMapping("/callback")
    public Mono<Void> receiveCallback(@RequestBody JournalCallback callback) {
        sseService.sendEvent(callback.getCorrelationId(), callback);
        return Mono.empty();
    }
}
