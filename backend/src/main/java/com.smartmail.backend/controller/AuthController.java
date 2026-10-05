package com.smartmail.backend.controller;

import com.smartmail.backend.model.AppUser;
import com.smartmail.backend.service.CurrentUserService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final CurrentUserService currentUserService;

    public AuthController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        AppUser user = currentUserService.get();
        return Map.of(
                "authenticated", true,
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "name", user.getName() == null ? "" : user.getName(),
                "picture", user.getPictureUrl() == null ? "" : user.getPictureUrl());
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("token", csrfToken.getToken());
    }
}
