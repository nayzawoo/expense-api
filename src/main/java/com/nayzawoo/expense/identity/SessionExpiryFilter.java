package com.nayzawoo.expense.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class SessionExpiryFilter extends OncePerRequestFilter {

    static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);
    static final Duration IDLE_LIFETIME = Duration.ofDays(7);

    private final Clock clock;

    SessionExpiryFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }
        HttpSession session = request.getSession(false);
        Instant authenticatedAt = instantAttribute(session, SessionAttributes.AUTHENTICATED_AT);
        Instant lastQualifying = instantAttribute(session, SessionAttributes.LAST_QUALIFYING_ACTIVITY_AT);
        if (lastQualifying == null) {
            lastQualifying = authenticatedAt;
        }
        Instant now = clock.instant();
        if (authenticatedAt == null
                || !now.isBefore(authenticatedAt.plus(ABSOLUTE_LIFETIME))
                || !now.isBefore(lastQualifying.plus(IDLE_LIFETIME))) {
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.sendError(401);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static Instant instantAttribute(HttpSession session, String name) {
        if (session == null) {
            return null;
        }
        Object value = session.getAttribute(name);
        return value instanceof Instant instant ? instant : null;
    }
}
