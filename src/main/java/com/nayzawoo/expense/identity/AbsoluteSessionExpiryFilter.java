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

class AbsoluteSessionExpiryFilter extends OncePerRequestFilter {

    static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);

    private final Clock clock;

    AbsoluteSessionExpiryFilter(Clock clock) {
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
        Object authenticatedAt = session == null ? null : session.getAttribute(SessionAttributes.AUTHENTICATED_AT);
        if (!(authenticatedAt instanceof Instant started)
                || !clock.instant().isBefore(started.plus(ABSOLUTE_LIFETIME))) {
            if (session != null) {
                session.invalidate();
            }
            SecurityContextHolder.clearContext();
            response.sendError(401);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
