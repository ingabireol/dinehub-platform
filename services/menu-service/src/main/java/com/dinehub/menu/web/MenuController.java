package com.dinehub.menu.web;

import com.dinehub.common.security.Roles;
import com.dinehub.menu.dto.MenuDtos;
import com.dinehub.menu.service.MenuService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Menu API.
 *
 * <p>Reads are open: a customer browses before logging in, and requiring auth to
 * see a menu would be a strange shop. Writes require ADMIN, checked here as well
 * as at the gateway.
 */
@RestController
@RequestMapping("/api/v1/menu")
@Tag(name = "Menu", description = "Categories and menu items")
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    // --- Categories ---------------------------------------------------------

    @GetMapping("/categories")
    @Operation(summary = "List categories")
    public List<MenuDtos.CategoryResponse> listCategories() {
        return menuService.listCategories();
    }

    @PostMapping("/categories")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Create a category (ADMIN)")
    public ResponseEntity<MenuDtos.CategoryResponse> createCategory(
            @Valid @RequestBody MenuDtos.CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(menuService.createCategory(request));
    }

    @DeleteMapping("/categories/{id}")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Delete an empty category (ADMIN)")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        menuService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }

    // --- Items --------------------------------------------------------------

    @GetMapping("/items")
    @Operation(summary = "List menu items",
            description = "availableOnly=true is what the customer-facing menu uses.")
    public List<MenuDtos.MenuItemResponse> listItems(
            @RequestParam(defaultValue = "false") boolean availableOnly) {
        return menuService.listItems(availableOnly);
    }

    @GetMapping("/items/{id}")
    @Operation(summary = "Get one menu item")
    public MenuDtos.MenuItemResponse getItem(@PathVariable UUID id) {
        return menuService.getItem(id);
    }

    @PostMapping("/items")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Create a menu item (ADMIN)")
    public ResponseEntity<MenuDtos.MenuItemResponse> createItem(
            @Valid @RequestBody MenuDtos.MenuItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(menuService.createItem(request));
    }

    @PutMapping("/items/{id}")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Update a menu item (ADMIN)")
    public MenuDtos.MenuItemResponse updateItem(@PathVariable UUID id,
                                                @Valid @RequestBody MenuDtos.MenuItemRequest request) {
        return menuService.updateItem(id, request);
    }

    @PatchMapping("/items/{id}/availability")
    @PreAuthorize(Roles.HAS_KITCHEN_OR_ADMIN)
    @Operation(summary = "Toggle availability (KITCHEN or ADMIN)",
            description = "Kitchen staff mark items off when they run out, so this is not ADMIN-only.")
    public MenuDtos.MenuItemResponse setAvailability(
            @PathVariable UUID id, @Valid @RequestBody MenuDtos.AvailabilityRequest request) {
        return menuService.setAvailability(id, request.available());
    }

    @DeleteMapping("/items/{id}")
    @PreAuthorize(Roles.HAS_ADMIN)
    @Operation(summary = "Delete a menu item (ADMIN)")
    public ResponseEntity<Void> deleteItem(@PathVariable UUID id) {
        menuService.deleteItem(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/items/pricing")
    @Operation(summary = "Price a batch of items",
            description = "Service-to-service: order-service calls this to snapshot prices "
                    + "onto an order at the moment it is placed.")
    public List<MenuDtos.PricedItem> priceItems(
            @Valid @RequestBody MenuDtos.PriceLookupRequest request) {
        return menuService.priceItems(request.itemIds());
    }
}
