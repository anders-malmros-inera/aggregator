package se.inera.aggregator.model;

import java.util.List;

public class AggregatedJournalResponse {
    private String patientId;
    private Integer respondents;
    private Integer errors;
    private List<JournalNote> notes;

    public AggregatedJournalResponse() {
    }

    public AggregatedJournalResponse(String patientId, Integer respondents, Integer errors, List<JournalNote> notes) {
        this.patientId = patientId;
        this.respondents = respondents;
        this.errors = errors;
        this.notes = notes;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public Integer getRespondents() {
        return respondents;
    }

    public void setRespondents(Integer respondents) {
        this.respondents = respondents;
    }

    public Integer getErrors() {
        return errors;
    }

    public void setErrors(Integer errors) {
        this.errors = errors;
    }

    public List<JournalNote> getNotes() {
        return notes;
    }

    public void setNotes(List<JournalNote> notes) {
        this.notes = notes;
    }
}
