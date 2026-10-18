package com.nayzawoo.expense.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

class AuthRefreshFilter extends OncePerRequestFilter {

    private final AppUserRepository users;
    private final SecurityContextRepository securityContexts;

    AuthRefreshFilter(AppUserRepository users, SecurityContextRepository securityContexts) {
        this.users = users;
        this.securityContexts = securityContexts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof ExpenseUser current)) {
            filterChain.doFilter(request, response);
            return;
        }
        AppUser latest = users.findById(current.getId()).orElse(null);
        if (latest == null
                || !"ACTIVE".equals(latest.getStatus())
                || latest.getSecurityVersion() != current.getSecurityVersion()) {
            reject(request, response);
            return;
        }
        ExpenseUser refreshed = new ExpenseUser(latest);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                refreshed, null, refreshed.getAuthorities()));
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        response.sendError(401);
    }
}
