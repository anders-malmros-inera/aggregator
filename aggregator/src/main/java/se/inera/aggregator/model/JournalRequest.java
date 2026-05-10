package se.inera.aggregator.model;

public class JournalRequest {
    private String patientId;
    private String delays;
    private Long timeoutMs;
    private String strategy; // "SSE", "WAIT_FOR_EVERYONE", or "DIRECT_TO_INBOX"
    private String inboxMode; // "CLIENT" or "AGGREGATOR" (used for DIRECT_TO_INBOX)
    private String inboxUrl; // used when inboxMode=CLIENT

    public JournalRequest() {
    }

    public JournalRequest(String patientId, String delays) {
        this.patientId = patientId;
        this.delays = delays;
    }

    public JournalRequest(String patientId, String delays, Long timeoutMs) {
        this.patientId = patientId;
        this.delays = delays;
        this.timeoutMs = timeoutMs;
    }

    public JournalRequest(String patientId, String delays, Long timeoutMs, String strategy) {
        this.patientId = patientId;
        this.delays = delays;
        this.timeoutMs = timeoutMs;
        this.strategy = strategy;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getDelays() {
        return delays;
    }

    public void setDelays(String delays) {
        this.delays = delays;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getInboxMode() {
        return inboxMode;
    }

    public void setInboxMode(String inboxMode) {
        this.inboxMode = inboxMode;
    }

    public String getInboxUrl() {
        return inboxUrl;
    }

    public void setInboxUrl(String inboxUrl) {
        this.inboxUrl = inboxUrl;
    }
}
