package com.smartmail.backend.repository;

import com.smartmail.backend.model.Email;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailRepository extends JpaRepository<Email, Long> {
}