package com.nayzawoo.expense.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    List<AppUser> findByHouseholdIdOrderByDisplayNameAscEmailAsc(UUID householdId);

    Optional<AppUser> findByIdAndHouseholdId(UUID id, UUID householdId);

    long countByHouseholdIdAndRoleAndStatus(UUID householdId, String role, String status);

    boolean existsByEmail(String email);
}
