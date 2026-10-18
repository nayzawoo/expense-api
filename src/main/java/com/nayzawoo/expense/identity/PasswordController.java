package com.nayzawoo.expense.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class PasswordController {

    private final PasswordChangeService passwordChangeService;

    PasswordController(PasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(
            @AuthenticationPrincipal ExpenseUser user,
            @RequestBody PasswordChangeRequest request,
            HttpServletRequest httpRequest) {
        passwordChangeService.changePassword(
                user.getId(),
                request == null ? null : request.currentPassword(),
                request == null ? null : request.newPassword());
        SecurityContextHolder.clearContext();
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    record PasswordChangeRequest(String currentPassword, String newPassword) {}
}
