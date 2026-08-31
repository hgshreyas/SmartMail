package com.smartmail.backend.controller;

import com.smartmail.backend.model.Email;
import com.smartmail.backend.repository.EmailRepository;
import com.smartmail.backend.service.EmailClassifierService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/emails")
public class EmailController {

    private final EmailRepository emailRepository;
    private final EmailClassifierService classifierService;

    public EmailController(
            EmailRepository emailRepository,
            EmailClassifierService classifierService) {

        this.emailRepository = emailRepository;
        this.classifierService = classifierService;
    }

    @GetMapping
    public List<Email> getAllEmails() {
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