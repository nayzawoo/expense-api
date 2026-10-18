package com.nayzawoo.expense.category;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findByHouseholdIdOrderBySortOrderAscNameAscIdAsc(UUID householdId);

    List<Category> findByHouseholdIdAndStatusOrderBySortOrderAscNameAscIdAsc(UUID householdId, String status);

    Optional<Category> findByIdAndHouseholdId(UUID id, UUID householdId);

    @Query(
            """
            SELECT CASE WHEN COUNT(c) > 0 THEN TRUE ELSE FALSE END
            FROM Category c
            WHERE c.householdId = :householdId
              AND LOWER(TRIM(c.name)) = LOWER(TRIM(:name))
              AND (:excludeId IS NULL OR c.id <> :excludeId)
            """)
    boolean existsNormalizedName(
            @Param("householdId") UUID householdId,
            @Param("name") String name,
            @Param("excludeId") UUID excludeId);
}
