package com.smartmail.backend.repository;

import com.smartmail.backend.model.Email;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailRepository extends JpaRepository<Email, Long> {

    Optional<Email> findByGmailMessageId(String gmailMessageId);
}