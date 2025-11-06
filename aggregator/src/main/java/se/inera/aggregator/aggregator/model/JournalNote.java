package se.inera.aggregator.model;

import java.time.LocalDateTime;

public class JournalNote {
    private String id;
    private String date;
    private String caregiverId;
    private String patientId;
    private String doctorId;
    private String note;

    public JournalNote() {
    }

    public JournalNote(String id, String date, String caregiverId, String patientId, String doctorId, String note) {
        this.id = id;
        this.date = date;
        this.caregiverId = caregiverId;
        this.patientId = patientId;
        this.doctorId = doctorId;
        this.note = note;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getCaregiverId() {
        return caregiverId;
    }

    public void setCaregiverId(String caregiverId) {
        this.caregiverId = caregiverId;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getDoctorId() {
        return doctorId;
    }

    public void setDoctorId(String doctorId) {
        this.doctorId = doctorId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
