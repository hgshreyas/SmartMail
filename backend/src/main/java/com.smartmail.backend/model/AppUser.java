package com.smartmail.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(name = "app_users", uniqueConstraints =
        @UniqueConstraint(name = "uk_app_users_google_subject", columnNames = "google_subject"))
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "google_subject", nullable = false, length = 255)
    private String googleSubject;

    @Column(length = 320)
    private String email;

    @Column(name = "display_name", length = 255)
    private String name;

    @Column(name = "picture_url", length = 2048)
    private String pictureUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
    }

    public AppUser(String googleSubject, String email, String name, String pictureUrl) {
        this.googleSubject = googleSubject;
        this.email = email;
        this.name = name;
        this.pictureUrl = pictureUrl;
    }

    public Long getId() { return id; }
    public String getGoogleSubject() { return googleSubject; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPictureUrl() { return pictureUrl; }
    public void setPictureUrl(String pictureUrl) { this.pictureUrl = pictureUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
