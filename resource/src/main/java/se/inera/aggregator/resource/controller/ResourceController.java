package se.inera.aggregator.resource.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import se.inera.aggregator.resource.model.JournalCommand;
import se.inera.aggregator.resource.model.JournalCallback;
import se.inera.aggregator.resource.service.ResourceService;

@RestController
@RequestMapping("/journals")
public class ResourceController {

    private final ResourceService resourceService;

    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @PostMapping
    public Mono<ResponseEntity<Void>> processRequest(@RequestBody JournalCommand command) {
        if (command.getDelay() == -1) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        return resourceService.processJournalRequest(command)
            .then(Mono.just(ResponseEntity.ok().<Void>build()))
            .onErrorReturn(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
    }
    
    @PostMapping("/direct")
    public Mono<JournalCallback> processDirectRequest(@RequestBody DirectJournalRequest request) {
        if (request.getDelay() == -1) {
            return Mono.just(new JournalCallback(
                resourceService.getResourceId(),
                request.getPatientId(),
                null,
                null,
                "REJECTED",
                null
            ));
        }

        return resourceService.processJournalRequestSynchronously(request.getPatientId(), request.getDelay());
    }
    
    // Helper class for direct requests
    public static class DirectJournalRequest {
        private String patientId;
        private int delay;
        
        public DirectJournalRequest() {
        }
        
        public String getPatientId() {
            return patientId;
        }
        
        public void setPatientId(String patientId) {
            this.patientId = patientId;
        }
        
        public int getDelay() {
            return delay;
        }
        
        public void setDelay(int delay) {
            this.delay = delay;
        }
    }
}

