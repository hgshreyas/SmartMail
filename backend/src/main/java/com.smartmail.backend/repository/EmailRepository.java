package com.smartmail.backend.repository;

import com.smartmail.backend.model.AppUser;
import com.smartmail.backend.model.Email;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailRepository extends JpaRepository<Email, Long> {
    List<Email> findByOwnerOrderByIdDesc(AppUser owner);
    List<Email> findByOwnerOrderByIdAsc(AppUser owner);
    Optional<Email> findByIdAndOwner(Long id, AppUser owner);
    Optional<Email> findByOwnerAndGmailMessageId(AppUser owner, String gmailMessageId);
    List<Email> findByOwnerAndActionAndAiReviewedFalseOrderByIdAsc(AppUser owner, String action);
}
