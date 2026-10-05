package com.smartmail.backend.service;

import com.smartmail.backend.model.AppUser;
import com.smartmail.backend.repository.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserService {
    private final AppUserRepository users;

    public CurrentUserService(AppUserRepository users) {
        this.users = users;
    }

    @Transactional
    public AppUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            throw new IllegalStateException("A Google sign-in is required.");
        }

        String subject = oidcUser.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new IllegalStateException("Google did not provide a stable account identifier.");
        }

        AppUser user = users.findByGoogleSubject(subject)
                .orElseGet(() -> new AppUser(subject, null, null, null));
        user.setEmail(oidcUser.getClaimAsString("email"));
        user.setName(oidcUser.getClaimAsString("name"));
        user.setPictureUrl(oidcUser.getClaimAsString("picture"));
        return users.save(user);
    }
}
