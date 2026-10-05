package com.smartmail.backend.model;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "emails", uniqueConstraints =
        @UniqueConstraint(name = "uk_emails_owner_gmail_message", columnNames = {"owner_id", "gmail_message_id"}))
public class Email {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String gmailMessageId;

    private String sender;

    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    private String category;

    private Double confidence;

    private String action;

    private boolean processed;

    private boolean aiReviewed;

    // Nullable so existing local historical rows survive the migration.
    // All new rows created through application code are assigned an owner.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    @JsonIgnore
    private AppUser owner;

    public Email() {
    }

    public Long getId() {
        return id;
    }

    public String getGmailMessageId() {
        return gmailMessageId;
    }

    public void setGmailMessageId(String gmailMessageId) {
        this.gmailMessageId = gmailMessageId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public boolean isProcessed() {
        return processed;
    }

    public void setProcessed(boolean processed) {
        this.processed = processed;
    }

    public boolean isAiReviewed() {
        return aiReviewed;
    }

    public void setAiReviewed(boolean aiReviewed) {
        this.aiReviewed = aiReviewed;
    }

    public AppUser getOwner() { return owner; }
    public void setOwner(AppUser owner) { this.owner = owner; }
}
