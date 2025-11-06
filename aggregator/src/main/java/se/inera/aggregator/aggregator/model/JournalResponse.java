package se.inera.aggregator.model;

public class JournalResponse {
    private Integer respondents;
    private String correlationId;

    public JournalResponse() {
    }

    public JournalResponse(Integer respondents, String correlationId) {
        this.respondents = respondents;
        this.correlationId = correlationId;
    }

    public Integer getRespondents() {
        return respondents;
    }

    public void setRespondents(Integer respondents) {
        this.respondents = respondents;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }
}
