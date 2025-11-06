package se.inera.aggregator.model;

import java.util.List;

public class JournalCallback {
    private String source;
    private String patientId;
    private String correlationId;
    private Integer delayMs;
    private String status;
    private List<JournalNote> notes;
    private Integer respondents;

    public JournalCallback() {
    }

    public JournalCallback(String source, String patientId, String correlationId, Integer delayMs, String status, List<JournalNote> notes) {
        this.source = source;
        this.patientId = patientId;
        this.correlationId = correlationId;
        this.delayMs = delayMs;
        this.status = status;
        this.notes = notes;
    }

    public JournalCallback(String source, String patientId, String correlationId, Integer delayMs, String status, List<JournalNote> notes, Integer respondents) {
        this.source = source;
        this.patientId = patientId;
        this.correlationId = correlationId;
        this.delayMs = delayMs;
        this.status = status;
        this.notes = notes;
        this.respondents = respondents;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Integer getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(Integer delayMs) {
        this.delayMs = delayMs;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<JournalNote> getNotes() {
        return notes;
    }

    public void setNotes(List<JournalNote> notes) {
        this.notes = notes;
    }

    public Integer getRespondents() {
        return respondents;
    }

    public void setRespondents(Integer respondents) {
        this.respondents = respondents;
    }
}
