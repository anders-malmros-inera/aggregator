package se.inera.aggregator.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.inera.aggregator.model.JournalCallback;
import se.inera.aggregator.service.InboxStoreService;

import java.util.List;

@RestController
@RequestMapping("/inbox")
@CrossOrigin(origins = "*")
public class AggregatorInboxController {

    private static final Logger logger = LoggerFactory.getLogger(AggregatorInboxController.class);

    private final InboxStoreService inboxStoreService;

    public AggregatorInboxController(InboxStoreService inboxStoreService) {
        this.inboxStoreService = inboxStoreService;
    }

    @PostMapping("/callback")
    public ResponseEntity<Void> receiveCallback(@RequestBody JournalCallback callback) {
        inboxStoreService.store(callback);
        logger.info("Aggregator inbox stored callback: correlationId={}, source={}",
            callback.getCorrelationId(), callback.getSource());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/messages")
    public ResponseEntity<List<JournalCallback>> getMessages(@RequestParam("correlationId") String correlationId) {
        return ResponseEntity.ok(inboxStoreService.getByCorrelationId(correlationId));
    }
}
