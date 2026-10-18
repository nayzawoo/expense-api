package com.nayzawoo.expense.identity;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface HouseholdRepository extends JpaRepository<Household, UUID> {}
