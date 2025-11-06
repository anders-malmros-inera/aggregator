package se.inera.aggregator.model;

public class JournalRequest {
    private String patientId;
    private String delays;

    public JournalRequest() {
    }

    public JournalRequest(String patientId, String delays) {
        this.patientId = patientId;
        this.delays = delays;
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
}
