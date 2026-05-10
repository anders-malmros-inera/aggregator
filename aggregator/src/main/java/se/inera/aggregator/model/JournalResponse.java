package se.inera.aggregator.model;

public class JournalResponse {
    private Integer respondents;
    private String correlationId;
    private String deliveryMode;
    private String inboxUrl;
    private String inboxReadUrl;

    public JournalResponse() {
    }

    public JournalResponse(Integer respondents, String correlationId) {
        this.respondents = respondents;
        this.correlationId = correlationId;
    }

    public JournalResponse(Integer respondents, String correlationId, String deliveryMode, String inboxUrl) {
        this.respondents = respondents;
        this.correlationId = correlationId;
        this.deliveryMode = deliveryMode;
        this.inboxUrl = inboxUrl;
    }

    public JournalResponse(Integer respondents, String correlationId, String deliveryMode, String inboxUrl, String inboxReadUrl) {
        this.respondents = respondents;
        this.correlationId = correlationId;
        this.deliveryMode = deliveryMode;
        this.inboxUrl = inboxUrl;
        this.inboxReadUrl = inboxReadUrl;
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

    public String getDeliveryMode() {
        return deliveryMode;
    }

    public void setDeliveryMode(String deliveryMode) {
        this.deliveryMode = deliveryMode;
    }

    public String getInboxUrl() {
        return inboxUrl;
    }

    public void setInboxUrl(String inboxUrl) {
        this.inboxUrl = inboxUrl;
    }

    public String getInboxReadUrl() {
        return inboxReadUrl;
    }

    public void setInboxReadUrl(String inboxReadUrl) {
        this.inboxReadUrl = inboxReadUrl;
    }
}
