package com.smartmail.backend.repository;

import com.smartmail.backend.model.Email;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailRepository extends JpaRepository<Email, Long> {

    Optional<Email> findByGmailMessageId(String gmailMessageId);

    /*
     * Returns emails that are still waiting for AI review.
     *
     * These will be used by the background pending-review processor
     * so old PENDING_REVIEW emails can gradually be processed instead
     * of remaining stuck forever.
     */
    List<Email> findByActionAndAiReviewedFalseOrderByIdAsc(
            String action
    );
}