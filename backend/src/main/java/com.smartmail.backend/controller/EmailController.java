package com.smartmail.backend.controller;

import com.smartmail.backend.model.AppUser;
import com.smartmail.backend.model.Email;
import com.smartmail.backend.repository.EmailRepository;
import com.smartmail.backend.service.CurrentUserService;
import com.smartmail.backend.service.EmailClassifierService;
import com.smartmail.backend.service.GmailService;
import com.smartmail.backend.service.PendingReviewService;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/emails")
public class EmailController {
    private final EmailRepository emailRepository;
    private final EmailClassifierService classifierService;
    private final GmailService gmailService;
    private final PendingReviewService pendingReviewService;
    private final CurrentUserService currentUserService;

    public EmailController(EmailRepository emailRepository,
                           EmailClassifierService classifierService,
                           GmailService gmailService,
                           PendingReviewService pendingReviewService,
                           CurrentUserService currentUserService) {
        this.emailRepository = emailRepository;
        this.classifierService = classifierService;
        this.gmailService = gmailService;
        this.pendingReviewService = pendingReviewService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<Email> getAllEmails() {
        return emailRepository.findByOwnerOrderByIdDesc(currentUserService.get());
    }

    @PostMapping(value = "/gmail/sync", produces = "application/json")
    public Map<String, Object> syncGmail(
            @RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient authorizedClient) {
        AppUser owner = currentUserService.get();
        requireMatchingGoogleAccount(authorizedClient, owner);
        CompletableFuture.runAsync(() -> {
            try {
                gmailService.getInboxMessages(authorizedClient, owner);
            } catch (Exception e) {
                System.err.println("SmartMail: Background Gmail processing failed: " + e.getMessage());
            }
        });
        return Map.of("started", true);
    }

    @GetMapping("/gmail/results")
    public List<Email> getGmailResults() {
        return emailRepository.findByOwnerOrderByIdDesc(currentUserService.get());
    }

    @GetMapping("/gmail/progress")
    public Map<String, Object> getGmailProgress() {
        return gmailService.getProcessingProgress(currentUserService.get());
    }

    // Kept for the local classifier/demo workflow. The submitted row is always
    // attached to the signed-in account; clients cannot set or change its owner.
    @PostMapping
    public Email addEmail(@RequestBody Email email) {
        email.setId(null);
        email.setOwner(currentUserService.get());
        return emailRepository.save(classifierService.classify(email));
    }

    @PostMapping("/test-ai-retry/{id}")
    public Map<String, Object> testAiRetry(@PathVariable Long id) {
        AppUser owner = currentUserService.get();
        Email email = emailRepository.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (email.isAiReviewed()) {
            return Map.of("started", false, "message", "Email is already AI reviewed.");
        }
        classifierService.classifyWithAiAsync(email, false, false, false, false, "", null);
        return Map.of("started", true);
    }

    @PostMapping("/pending-review/process")
    public Map<String, Object> processPendingReviews(
            @RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient authorizedClient) {
        AppUser owner = currentUserService.get();
        requireMatchingGoogleAccount(authorizedClient, owner);
        boolean started = pendingReviewService.startProcessing(authorizedClient, owner);
        return Map.of("started", started, "progress", pendingReviewService.getProgress(owner));
    }

    @GetMapping("/pending-review/progress")
    public Map<String, Object> getPendingReviewProgress() {
        return pendingReviewService.getProgress(currentUserService.get());
    }

    @GetMapping("/{id}")
    public Email getEmail(@PathVariable Long id) {
        return emailRepository.findByIdAndOwner(id, currentUserService.get())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping("/{id}/keep")
    public Email keepEmail(@PathVariable Long id,
                           @RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient authorizedClient) {
        AppUser owner = currentUserService.get();
        requireMatchingGoogleAccount(authorizedClient, owner);
        return gmailService.keepEmail(authorizedClient, id, owner);
    }

    @PostMapping("/{id}/trash")
    public Email trashEmail(@PathVariable Long id,
                            @RegisteredOAuth2AuthorizedClient("google") OAuth2AuthorizedClient authorizedClient) {
        AppUser owner = currentUserService.get();
        requireMatchingGoogleAccount(authorizedClient, owner);
        return gmailService.trashEmail(authorizedClient, id, owner);
    }

    @DeleteMapping("/{id}")
    public void deleteEmail(@PathVariable Long id) {
        Email email = emailRepository.findByIdAndOwner(id, currentUserService.get())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        emailRepository.delete(email);
    }

    private void requireMatchingGoogleAccount(OAuth2AuthorizedClient client, AppUser owner) {
        if (client == null || !owner.getGoogleSubject().equals(client.getPrincipalName())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Google account does not match the signed-in user.");
        }
    }
}
