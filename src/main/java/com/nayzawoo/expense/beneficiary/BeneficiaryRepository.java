package com.nayzawoo.expense.beneficiary;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    List<Beneficiary> findByHouseholdIdOrderBySortOrderAscNameAscIdAsc(UUID householdId);

    List<Beneficiary> findByHouseholdIdAndStatusOrderBySortOrderAscNameAscIdAsc(UUID householdId, String status);

    Optional<Beneficiary> findByIdAndHouseholdId(UUID id, UUID householdId);

    @Query(
            """
            SELECT CASE WHEN COUNT(b) > 0 THEN TRUE ELSE FALSE END
            FROM Beneficiary b
            WHERE b.householdId = :householdId
              AND LOWER(TRIM(b.name)) = LOWER(TRIM(:name))
              AND (:excludeId IS NULL OR b.id <> :excludeId)
            """)
    boolean existsNormalizedName(
            @Param("householdId") UUID householdId,
            @Param("name") String name,
            @Param("excludeId") UUID excludeId);
}
