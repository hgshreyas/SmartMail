package com.smartmail.backend.controller;

import com.smartmail.backend.model.Email;
import com.smartmail.backend.repository.EmailRepository;
import com.smartmail.backend.service.EmailClassifierService;
import com.smartmail.backend.service.GmailService;

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/emails")
public class EmailController {

    private final EmailRepository emailRepository;
    private final EmailClassifierService classifierService;
    private final GmailService gmailService;

    public EmailController(
            EmailRepository emailRepository,
            EmailClassifierService classifierService,
            GmailService gmailService) {

        this.emailRepository = emailRepository;
        this.classifierService = classifierService;
        this.gmailService = gmailService;
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

        return gmailService.getInboxMessages(authorizedClient);
    }

    /*
     * DB-ONLY endpoint.
     *
     * This does NOT fetch Gmail.
     * This does NOT run Ollama.
     * It only returns the latest results stored in PostgreSQL.
     */
    @GetMapping("/gmail/results")
    public List<Email> getGmailResults() {
        return emailRepository.findAll();
    }

    /*
     * Add an email manually.
     */
    @PostMapping
    public Email addEmail(@RequestBody Email email) {

        email = classifierService.classify(email);

        return emailRepository.save(email);
    }

    /*
     * Get one email by database ID.
     */
    @GetMapping("/{id}")
    public Email getEmail(@PathVariable Long id) {
        return emailRepository.findById(id).orElse(null);
    }

    /*
     * Human review:
     *
     * Keep the email.
     *
     * This updates both Gmail and PostgreSQL.
     */
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

    /*
     * Human review:
     *
     * Move the email to Gmail Trash.
     *
     * This updates both Gmail and PostgreSQL.
     */
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

    /*
     * Delete an email from the SmartMail database.
     */
    @DeleteMapping("/{id}")
    public void deleteEmail(@PathVariable Long id) {
        emailRepository.deleteById(id);
    }
}