package com.dinehub.menu.service;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.menu.dto.MenuDtos;
import com.dinehub.menu.entity.Category;
import com.dinehub.menu.entity.MenuItem;
import com.dinehub.menu.repository.CategoryRepository;
import com.dinehub.menu.repository.MenuItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuService.class);

    private final CategoryRepository categories;
    private final MenuItemRepository items;
    private final RabbitTemplate rabbit;

    public MenuService(CategoryRepository categories, MenuItemRepository items,
                       RabbitTemplate rabbit) {
        this.categories = categories;
        this.items = items;
        this.rabbit = rabbit;
    }

    // --- Categories ---------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MenuDtos.CategoryResponse> listCategories() {
        return categories.findAllByOrderByDisplayOrderAscNameAsc().stream()
                .map(c -> new MenuDtos.CategoryResponse(c.getId(), c.getName(), c.getDescription(),
                        c.getDisplayOrder(), items.findByCategoryId(c.getId()).size()))
                .toList();
    }

    @Transactional
    public MenuDtos.CategoryResponse createCategory(MenuDtos.CategoryRequest request) {
        if (categories.existsByNameIgnoreCase(request.name())) {
            throw new ApiExceptions.ConflictException(
                    "A category named '%s' already exists".formatted(request.name()));
        }
        Category saved = categories.save(
                new Category(request.name().trim(), request.description(), request.displayOrder()));
        log.info("Created category {} ({})", saved.getId(), saved.getName());
        return new MenuDtos.CategoryResponse(saved.getId(), saved.getName(), saved.getDescription(),
                saved.getDisplayOrder(), 0);
    }

    @Transactional
    public void deleteCategory(UUID categoryId) {
        Category category = categories.findById(categoryId)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Category", categoryId));

        // Refuse rather than cascade. Deleting a category with items would
        // silently remove things that are on live orders.
        if (items.existsByCategoryId(categoryId)) {
            throw new ApiExceptions.ConflictException(
                    "Category '%s' still has items. Move or delete them first."
                            .formatted(category.getName()));
        }
        categories.delete(category);
        log.info("Deleted category {}", categoryId);
    }

    // --- Items --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<MenuDtos.MenuItemResponse> listItems(boolean availableOnly) {
        List<MenuItem> found = availableOnly
                ? items.findAvailableWithCategory()
                : items.findAllWithCategory();
        return found.stream().map(MenuService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public MenuDtos.MenuItemResponse getItem(UUID itemId) {
        return items.findWithCategoryById(itemId)
                .map(MenuService::toResponse)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Menu item", itemId));
    }

    @Transactional
    public MenuDtos.MenuItemResponse createItem(MenuDtos.MenuItemRequest request) {
        Category category = categories.findById(request.categoryId())
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Category", request.categoryId()));

        MenuItem item = items.save(new MenuItem(category, request.name().trim(),
                request.description(), request.price(), request.preparationMinutes(),
                request.imageUrl()));

        log.info("Created menu item {} ({}) at {}", item.getId(), item.getName(), item.getPrice());
        publishUpdated(item);
        return toResponse(item);
    }

    @Transactional
    public MenuDtos.MenuItemResponse updateItem(UUID itemId, MenuDtos.MenuItemRequest request) {
        MenuItem item = items.findWithCategoryById(itemId)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Menu item", itemId));
        Category category = categories.findById(request.categoryId())
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Category", request.categoryId()));

        item.update(category, request.name().trim(), request.description(), request.price(),
                request.preparationMinutes(), request.imageUrl());

        log.info("Updated menu item {} ({})", item.getId(), item.getName());
        publishUpdated(item);
        return toResponse(item);
    }

    @Transactional
    public MenuDtos.MenuItemResponse setAvailability(UUID itemId, boolean available) {
        MenuItem item = items.findWithCategoryById(itemId)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Menu item", itemId));

        item.setAvailable(available);
        log.info("Menu item {} is now {}", itemId, available ? "available" : "unavailable");
        publishUpdated(item);
        return toResponse(item);
    }

    @Transactional
    public void deleteItem(UUID itemId) {
        MenuItem item = items.findById(itemId)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Menu item", itemId));
        items.delete(item);
        log.info("Deleted menu item {}", itemId);
    }

    /**
     * Prices a batch of item ids for order-service.
     *
     * <p>Returns only the items that exist. The caller compares what it asked
     * for against what came back and rejects the order if anything is missing —
     * better than this service guessing what an absent item should cost.
     */
    @Transactional(readOnly = true)
    public List<MenuDtos.PricedItem> priceItems(List<UUID> itemIds) {
        return items.findByIdIn(itemIds).stream()
                .map(i -> new MenuDtos.PricedItem(i.getId(), i.getName(), i.getPrice(),
                        i.isAvailable(), i.getPreparationMinutes()))
                .toList();
    }

    private void publishUpdated(MenuItem item) {
        var event = new EventPayloads.MenuItemUpdated(
                EventPayloads.EventMeta.now(MDC.get("traceId")),
                item.getId(), item.getName(), item.getPrice(), item.isAvailable());
        try {
            rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.MENU_ITEM_UPDATED, event);
        } catch (Exception e) {
            log.error("Could not publish menu.item.updated for {}", item.getId(), e);
        }
    }

    private static MenuDtos.MenuItemResponse toResponse(MenuItem item) {
        return new MenuDtos.MenuItemResponse(
                item.getId(), item.getCategory().getId(), item.getCategory().getName(),
                item.getName(), item.getDescription(), item.getPrice(), item.isAvailable(),
                item.getPreparationMinutes(), item.getImageUrl(), item.getUpdatedAt());
    }
}
