package com.smartmail.backend.service;

import com.smartmail.backend.model.Email;
import org.springframework.stereotype.Service;

@Service
public class EmailClassifierService {

    public Email classify(Email email) {

        String subject = email.getSubject().toLowerCase();
        String body = email.getBody().toLowerCase();

        String text = subject + " " + body;

        if (text.contains("win") ||
                text.contains("prize") ||
                text.contains("lottery") ||
                text.contains("free money") ||
                text.contains("claim now")) {

            email.setCategory("SPAM");
            email.setConfidence(0.95);
            email.setAction("DELETE");

        } else if (text.contains("sale") ||
                text.contains("discount") ||
                text.contains("offer") ||
                text.contains("coupon")) {

            email.setCategory("PROMOTIONAL");
            email.setConfidence(0.90);
            email.setAction("ARCHIVE");

        } else if (text.contains("interview") ||
                text.contains("meeting") ||
                text.contains("deadline") ||
                text.contains("project")) {

            email.setCategory("IMPORTANT");
            email.setConfidence(0.90);
            email.setAction("KEEP");

        } else {

            email.setCategory("OTHER");
            email.setConfidence(0.60);
            email.setAction("KEEP");
        }

        email.setProcessed(true);

        return email;
    }
}