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
     *
     * The frontend will use this endpoint to get updated
     * classification results after the asynchronous AI review finishes.
     */
    @GetMapping("/gmail/results")
    public List<Email> getGmailResults() {
        return emailRepository.findAll();
    }

    @PostMapping
    public Email addEmail(@RequestBody Email email) {

        email = classifierService.classify(email);

        return emailRepository.save(email);
    }

    @GetMapping("/{id}")
    public Email getEmail(@PathVariable Long id) {
        return emailRepository.findById(id).orElse(null);
    }

    @DeleteMapping("/{id}")
    public void deleteEmail(@PathVariable Long id) {
        emailRepository.deleteById(id);
    }
}