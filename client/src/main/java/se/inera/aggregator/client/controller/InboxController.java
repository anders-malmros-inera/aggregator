package se.inera.aggregator.client.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import se.inera.aggregator.client.service.ClientInboxStoreService;

import java.util.List;
import java.util.Map;

/**
 * Demo inbox endpoint used by DIRECT_TO_INBOX AGGREGATOR mode.
 *
 * In production, this should be replaced with a persistent inbox service.
 */
@RestController
@RequestMapping("/inbox")
public class InboxController {

    private static final Logger logger = LoggerFactory.getLogger(InboxController.class);
    private final ClientInboxStoreService clientInboxStoreService;

    public InboxController(ClientInboxStoreService clientInboxStoreService) {
        this.clientInboxStoreService = clientInboxStoreService;
    }

    @PostMapping("/callback")
    public ResponseEntity<Void> receiveCallback(@RequestBody Map<String, Object> payload) {
        clientInboxStoreService.store(payload);
        Object correlationId = payload.get("correlationId");
        Object source = payload.get("source");
        logger.info("Inbox callback received: correlationId={}, source={}", correlationId, source);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/messages")
    public ResponseEntity<List<Map<String, Object>>> getMessages(@RequestParam("correlationId") String correlationId) {
        return ResponseEntity.ok(clientInboxStoreService.getByCorrelationId(correlationId));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
