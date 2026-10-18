package com.nayzawoo.expense.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final Clock clock;

    AuthController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            Clock clock) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.clock = clock;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/login")
    CurrentUserResponse login(
            @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        if (request == null || request.email() == null || request.email().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            AppUserDetailsService.normalize(request.email()), request.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);
            Instant now = clock.instant();
            HttpSession session = httpRequest.getSession();
            session.setAttribute(SessionAttributes.AUTHENTICATED_AT, now);
            session.setAttribute(SessionAttributes.LAST_QUALIFYING_ACTIVITY_AT, now);
            return CurrentUserResponse.from((ExpenseUser) authentication.getPrincipal());
        } catch (AuthenticationException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    @PostMapping("/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void activity(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Instant authenticatedAt = instantAttribute(session, SessionAttributes.AUTHENTICATED_AT);
        Instant lastQualifying = instantAttribute(session, SessionAttributes.LAST_QUALIFYING_ACTIVITY_AT);
        if (lastQualifying == null) {
            lastQualifying = authenticatedAt;
        }
        Instant now = clock.instant();
        if (authenticatedAt == null
                || !now.isBefore(authenticatedAt.plus(SessionExpiryFilter.ABSOLUTE_LIFETIME))
                || !now.isBefore(lastQualifying.plus(SessionExpiryFilter.IDLE_LIFETIME))) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (lastQualifying == null || now.isAfter(lastQualifying)) {
            session.setAttribute(SessionAttributes.LAST_QUALIFYING_ACTIVITY_AT, now);
        }
    }

    @GetMapping("/me")
    CurrentUserResponse me(@AuthenticationPrincipal ExpenseUser user) {
        return CurrentUserResponse.from(user);
    }

    private static Instant instantAttribute(HttpSession session, String name) {
        Object value = session.getAttribute(name);
        return value instanceof Instant instant ? instant : null;
    }

    record LoginRequest(String email, String password) {}

    record CsrfResponse(String token, String headerName) {}

    record CurrentUserResponse(
            String id, String email, String displayName, String role, boolean mustChangePassword) {

        static CurrentUserResponse from(ExpenseUser user) {
            return new CurrentUserResponse(
                    user.getId().toString(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getRole(),
                    user.mustChangePassword());
        }
    }
}
