package com.nayzawoo.expense.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
class ProfileController {

    private final ProfileService profileService;

    ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    ProfileResponse get(@AuthenticationPrincipal ExpenseUser actor) {
        return ProfileResponse.from(profileService.getProfile(actor));
    }

    @PutMapping
    ProfileResponse update(
            @AuthenticationPrincipal ExpenseUser actor,
            @RequestBody UpdateProfileRequest request,
            HttpServletRequest httpRequest) {
        ProfileService.ProfileUpdateResult result = profileService.updateProfile(
                actor,
                request == null ? null : request.displayName(),
                request == null ? null : request.email(),
                request == null ? null : request.currentPassword());
        if (result.sessionsRevoked()) {
            SecurityContextHolder.clearContext();
            HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        return ProfileResponse.from(result.user());
    }

    record UpdateProfileRequest(String displayName, String email, String currentPassword) {}

    record ProfileResponse(
            String id,
            String email,
            String displayName,
            String role,
            String status,
            boolean mustChangePassword,
            boolean passwordChangeCompleted) {

        static ProfileResponse from(AppUser user) {
            return new ProfileResponse(
                    user.getId().toString(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getRole(),
                    user.getStatus(),
                    user.isMustChangePassword(),
                    user.getPasswordChangedAt() != null);
        }
    }
}
