package com.nayzawoo.expense.identity;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/users")
class HouseholdUserController {

    private final HouseholdUserService householdUsers;

    HouseholdUserController(HouseholdUserService householdUsers) {
        this.householdUsers = householdUsers;
    }

    @GetMapping
    List<UserResponse> list(@AuthenticationPrincipal ExpenseUser actor, @PathVariable UUID householdId) {
        return householdUsers.listUsers(actor, householdId).stream()
                .map(UserResponse::from)
                .toList();
    }

    @GetMapping("/{userId}")
    UserResponse detail(
            @AuthenticationPrincipal ExpenseUser actor, @PathVariable UUID householdId, @PathVariable UUID userId) {
        return UserResponse.from(householdUsers.getUser(actor, householdId, userId));
    }

    @PostMapping
    ResponseEntity<UserResponse> create(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestBody CreateUserRequest request) {
        AppUser created = householdUsers.createUser(
                actor,
                householdId,
                request == null ? null : request.displayName(),
                request == null ? null : request.email(),
                request == null ? null : request.temporaryPassword(),
                request == null ? null : request.role());
        return ResponseEntity.created(URI.create("/api/v1/households/" + householdId + "/users/" + created.getId()))
                .body(UserResponse.from(created));
    }

    @PatchMapping("/{userId}")
    UserResponse update(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID userId,
            @RequestBody UpdateUserRequest request) {
        return UserResponse.from(householdUsers.updateUser(
                actor,
                householdId,
                userId,
                request == null ? null : request.displayName(),
                request == null ? null : request.role(),
                request == null ? null : request.status(),
                request == null ? null : request.email()));
    }

    @PostMapping("/{userId}/temporary-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void temporaryPassword(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID userId,
            @RequestBody TemporaryPasswordRequest request) {
        householdUsers.assignTemporaryPassword(
                actor, householdId, userId, request == null ? null : request.temporaryPassword());
    }

    record CreateUserRequest(String displayName, String email, String temporaryPassword, String role) {}

    record UpdateUserRequest(String displayName, String role, String status, String email) {}

    record TemporaryPasswordRequest(String temporaryPassword) {}

    record UserResponse(
            String id,
            String email,
            String displayName,
            String role,
            String status,
            boolean mustChangePassword,
            boolean passwordChangeCompleted) {

        static UserResponse from(AppUser user) {
            return new UserResponse(
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
