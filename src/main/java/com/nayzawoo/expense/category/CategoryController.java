package com.nayzawoo.expense.category;

import com.nayzawoo.expense.identity.ExpenseUser;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/categories")
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    List<CategoryResponse> list(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestParam(required = false) String status) {
        return categories.list(actor, householdId, status).stream().map(CategoryResponse::from).toList();
    }

    @GetMapping("/{categoryId}")
    CategoryResponse detail(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID categoryId) {
        return CategoryResponse.from(categories.get(actor, householdId, categoryId));
    }

    @PostMapping
    ResponseEntity<CategoryResponse> create(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestBody CreateCategoryRequest request) {
        Category created = categories.create(
                actor,
                householdId,
                request == null ? null : request.name(),
                request == null ? null : request.color(),
                request == null ? null : request.iconKey(),
                request == null ? null : request.sortOrder());
        return ResponseEntity.created(URI.create(
                        "/api/v1/households/" + householdId + "/categories/" + created.getId()))
                .body(CategoryResponse.from(created));
    }

    @PutMapping("/{categoryId}")
    CategoryResponse update(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID categoryId,
            @RequestBody UpdateCategoryRequest request) {
        return CategoryResponse.from(categories.update(
                actor,
                householdId,
                categoryId,
                request == null ? null : request.name(),
                request == null ? null : request.color(),
                request == null ? null : request.iconKey(),
                request == null ? null : request.sortOrder()));
    }

    @PatchMapping("/{categoryId}/status")
    CategoryResponse updateStatus(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID categoryId,
            @RequestBody StatusRequest request) {
        return CategoryResponse.from(categories.updateStatus(
                actor, householdId, categoryId, request == null ? null : request.status()));
    }

    record CreateCategoryRequest(String name, String color, String iconKey, Integer sortOrder) {}

    record UpdateCategoryRequest(String name, String color, String iconKey, Integer sortOrder) {}

    record StatusRequest(String status) {}

    record CategoryResponse(
            String id, String name, String color, String iconKey, int sortOrder, String status, long version) {

        static CategoryResponse from(Category category) {
            return new CategoryResponse(
                    category.getId().toString(),
                    category.getName(),
                    category.getColor(),
                    category.getIconKey(),
                    category.getSortOrder(),
                    category.getStatus(),
                    category.getVersion());
        }
    }
}
