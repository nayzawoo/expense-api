package com.nayzawoo.expense.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByHouseholdIdOrderBySortOrderAscNameAscIdAsc(UUID householdId);

    Optional<Account> findByIdAndHouseholdId(UUID id, UUID householdId);
}
