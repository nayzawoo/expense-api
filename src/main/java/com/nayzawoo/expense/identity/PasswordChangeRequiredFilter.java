package com.nayzawoo.expense.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof ExpenseUser user
                && user.mustChangePassword()
                && !allowed(request)) {
            response.sendError(403);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean allowed(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("GET".equals(method) && ("/api/v1/auth/csrf".equals(path) || "/api/v1/auth/me".equals(path))) {
            return true;
        }
        if ("PUT".equals(method) && "/api/v1/password".equals(path)) {
            return true;
        }
        if ("POST".equals(method) && "/api/v1/auth/logout".equals(path)) {
            return true;
        }
        return "GET".equals(method) && (path.equals("/actuator/health") || path.startsWith("/actuator/health/"));
    }
}
