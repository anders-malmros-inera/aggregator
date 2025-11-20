package se.inera.aggregator.model;

public class JournalRequest {
    private String patientId;
    private String delays;
    private Long timeoutMs;
    private String strategy; // "SSE" or "WAIT_FOR_EVERYONE"

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
}
