package com.smartmail.backend.controller;

import com.smartmail.backend.model.Email;
import com.smartmail.backend.repository.EmailRepository;
import com.smartmail.backend.service.EmailClassifierService;
import com.smartmail.backend.service.GmailService;
import com.smartmail.backend.service.PendingReviewService;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.*;

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

    public EmailController(
            EmailRepository emailRepository,
            EmailClassifierService classifierService,
            GmailService gmailService,
            PendingReviewService pendingReviewService) {

        this.emailRepository = emailRepository;
        this.classifierService = classifierService;
        this.gmailService = gmailService;
        this.pendingReviewService = pendingReviewService;
    }

    @GetMapping
    public List<Email> getAllEmails() {
        return emailRepository.findAll();
    }

    @GetMapping("/")
    public String home() {
        return "SmartMail is running!";
    }

    @GetMapping(value = "/gmail/test", produces = "text/html")
    public String testGmail(
            @RegisteredOAuth2AuthorizedClient("google")
            OAuth2AuthorizedClient authorizedClient) {

        CompletableFuture.runAsync(() -> {
            try {

                gmailService.getInboxMessages(
                        authorizedClient
                );

                System.out.println(
                        "SmartMail: Background Gmail processing completed."
                );

            } catch (Exception e) {

                System.err.println(
                        "SmartMail: Background Gmail processing failed: "
                                + e.getMessage()
                );
            }
        });

        return "<h2>SmartMail Gmail processing started in the background.</h2>"
                + "<p>You can continue using SmartMail while processing runs.</p>";
    }

    @GetMapping("/gmail/results")
    public List<Email> getGmailResults() {
        return emailRepository.findAll();
    }

    @GetMapping("/gmail/progress")
    public Map<String, Object> getGmailProgress() {
        return gmailService.getProcessingProgress();
    }

    @PostMapping
    public Email addEmail(
            @RequestBody Email email) {

        email =
                classifierService.classify(
                        email
                );

        return emailRepository.save(
                email
        );
    }

    /*
     * TEST ENDPOINT:
     *
     * Used only to verify SmartMail AI retry/error handling
     * without starting a Gmail batch.
     *
     * Example:
     * http://localhost:8080/emails/test-ai-retry/366
     */
    @PostMapping("/test-ai-retry/{id}")
    public String testAiRetry(
            @PathVariable Long id) {

        Email email =
                emailRepository
                        .findById(id)
                        .orElse(null);

        if (email == null) {

            return "Email not found: "
                    + id;
        }

        if (email.isAiReviewed()) {

            return "Email is already AI reviewed. Use an email with "
                    + "aiReviewed=false.";
        }

        classifierService.classifyWithAiAsync(
                email,
                false,
                false,
                false,
                false,
                "",
                null
        );

        return "AI retry test started for email ID "
                + id
                + ". Check the backend console.";
    }

    // ============================================================
    // PENDING REVIEW BACKLOG
    // ============================================================

    /*
     * Starts background processing of emails that were left
     * PENDING_REVIEW because Ollama concurrency slots were full.
     *
     * Only one backlog processor may run at a time.
     */
    @PostMapping("/pending-review/process")
    public Map<String, Object> processPendingReviews(
            @RegisteredOAuth2AuthorizedClient("google")
            OAuth2AuthorizedClient authorizedClient) {

        boolean started =
                pendingReviewService.startProcessing(
                        authorizedClient
                );

        return Map.of(
                "started",
                started,

                "progress",
                pendingReviewService.getProgress()
        );
    }

    /*
     * Returns live progress for the background backlog processor.
     */
    @GetMapping("/pending-review/progress")
    public Map<String, Object> getPendingReviewProgress() {

        return pendingReviewService.getProgress();
    }

    @GetMapping("/{id}")
    public Email getEmail(
            @PathVariable Long id) {

        return emailRepository
                .findById(id)
                .orElse(null);
    }

    @PostMapping("/{id}/keep")
    public Email keepEmail(
            @PathVariable Long id,
            @RegisteredOAuth2AuthorizedClient("google")
            OAuth2AuthorizedClient authorizedClient) {

        return gmailService.keepEmail(
                authorizedClient,
                id
        );
    }

    @PostMapping("/{id}/trash")
    public Email trashEmail(
            @PathVariable Long id,
            @RegisteredOAuth2AuthorizedClient("google")
            OAuth2AuthorizedClient authorizedClient) {

        return gmailService.trashEmail(
                authorizedClient,
                id
        );
    }

    @DeleteMapping("/{id}")
    public void deleteEmail(
            @PathVariable Long id) {

        emailRepository.deleteById(
                id
        );
    }
}