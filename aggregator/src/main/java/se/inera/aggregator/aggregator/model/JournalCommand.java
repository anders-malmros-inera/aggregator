package se.inera.aggregator.model;

public class JournalCommand {
    private String patientId;
    private Integer delay;
    private String callbackUrl;
    private String correlationId;

    public JournalCommand() {
    }

    public JournalCommand(String patientId, Integer delay, String callbackUrl, String correlationId) {
        this.patientId = patientId;
        this.delay = delay;
        this.callbackUrl = callbackUrl;
        this.correlationId = correlationId;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public Integer getDelay() {
        return delay;
    }

    public void setDelay(Integer delay) {
        this.delay = delay;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
