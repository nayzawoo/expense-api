package com.nayzawoo.expense.identity;

import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BootstrapService {

    private final HouseholdRepository households;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    BootstrapService(HouseholdRepository households, AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.households = households;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    BootstrapResult bootstrap(String householdName, String adminEmail, String adminDisplayName, String adminPassword) {
        if (householdName == null
                || householdName.isBlank()
                || adminEmail == null
                || adminEmail.isBlank()
                || adminDisplayName == null
                || adminDisplayName.isBlank()
                || adminPassword == null
                || adminPassword.isBlank()
                || adminPassword.length() < PasswordChangeService.MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Bootstrap requires household name, admin email, display name, and password");
        }
        if (households.count() > 0) {
            throw new IllegalStateException("Household already exists");
        }
        String email = AppUserDetailsService.normalize(adminEmail);
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Household household = households.save(Household.create(householdId, householdName.trim()));
        AppUser admin = users.save(AppUser.createBootstrapAdmin(
                userId,
                household.getId(),
                email,
                adminDisplayName.trim(),
                passwordEncoder.encode(adminPassword)));
        return new BootstrapResult(household.getId(), admin.getId(), admin.getEmail());
    }

    record BootstrapResult(UUID householdId, UUID adminUserId, String adminEmail) {}
}
