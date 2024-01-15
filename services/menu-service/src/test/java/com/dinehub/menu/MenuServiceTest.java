package com.dinehub.menu;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.menu.dto.MenuDtos;
import com.dinehub.menu.entity.Category;
import com.dinehub.menu.entity.MenuItem;
import com.dinehub.menu.repository.CategoryRepository;
import com.dinehub.menu.repository.MenuItemRepository;
import com.dinehub.menu.service.MenuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock
    private CategoryRepository categories;

    @Mock
    private MenuItemRepository items;

    @Mock
    private RabbitTemplate rabbit;

    private MenuService menuService;
    private Category mains;

    @BeforeEach
    void setUp() {
        menuService = new MenuService(categories, items, rabbit);
        mains = new Category("Mains", "Hearty main courses", 2);
    }

    @Nested
    @DisplayName("categories")
    class Categories {

        @Test
        @DisplayName("creates a category")
        void createsCategory() {
            when(categories.existsByNameIgnoreCase("Desserts")).thenReturn(false);
            when(categories.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

            var created = menuService.createCategory(
                    new MenuDtos.CategoryRequest("Desserts", "Something sweet", 3));

            assertThat(created.name()).isEqualTo("Desserts");
            assertThat(created.itemCount()).isZero();
        }

        @Test
        @DisplayName("refuses a duplicate name regardless of case")
        void refusesDuplicateName() {
            when(categories.existsByNameIgnoreCase("mains")).thenReturn(true);

            assertThatThrownBy(() -> menuService.createCategory(
                    new MenuDtos.CategoryRequest("mains", null, 1)))
                    .isInstanceOf(ApiExceptions.ConflictException.class);

            verify(categories, never()).save(any());
        }

        @Test
        @DisplayName("refuses to delete a category that still has items")
        void refusesToDeleteNonEmptyCategory() {
            // Cascading here would silently remove items that may be on live
            // orders, so the delete fails loudly instead.
            UUID id = mains.getId();
            when(categories.findById(id)).thenReturn(Optional.of(mains));
            when(items.existsByCategoryId(id)).thenReturn(true);

            assertThatThrownBy(() -> menuService.deleteCategory(id))
                    .isInstanceOf(ApiExceptions.ConflictException.class)
                    .hasMessageContaining("still has items");

            verify(categories, never()).delete(any());
        }

        @Test
        @DisplayName("deletes an empty category")
        void deletesEmptyCategory() {
            UUID id = mains.getId();
            when(categories.findById(id)).thenReturn(Optional.of(mains));
            when(items.existsByCategoryId(id)).thenReturn(false);

            menuService.deleteCategory(id);

            verify(categories).delete(mains);
        }

        @Test
        @DisplayName("deleting an unknown category is a 404")
        void deleteUnknownCategory() {
            UUID id = UUID.randomUUID();
            when(categories.findById(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> menuService.deleteCategory(id))
                    .isInstanceOf(ApiExceptions.NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("items")
    class Items {

        @Test
        @DisplayName("creates an item under an existing category")
        void createsItem() {
            when(categories.findById(mains.getId())).thenReturn(Optional.of(mains));
            when(items.save(any(MenuItem.class))).thenAnswer(i -> i.getArgument(0));

            var created = menuService.createItem(new MenuDtos.MenuItemRequest(
                    mains.getId(), "Grilled Tilapia", "Whole fish",
                    new BigDecimal("12.50"), 25, null));

            assertThat(created.name()).isEqualTo("Grilled Tilapia");
            assertThat(created.price()).isEqualByComparingTo("12.50");
            assertThat(created.available()).isTrue();
            assertThat(created.categoryName()).isEqualTo("Mains");
        }

        @Test
        @DisplayName("a price is stored at exactly two decimal places")
        void normalisesPriceScale() {
            // Money with a drifting scale produces totals that do not add up on
            // a receipt, which customers notice.
            when(categories.findById(mains.getId())).thenReturn(Optional.of(mains));
            when(items.save(any(MenuItem.class))).thenAnswer(i -> i.getArgument(0));

            var created = menuService.createItem(new MenuDtos.MenuItemRequest(
                    mains.getId(), "Odd Price", null, new BigDecimal("9.5"), 10, null));

            assertThat(created.price().scale()).isEqualTo(2);
            assertThat(created.price()).isEqualByComparingTo("9.50");
        }

        @Test
        @DisplayName("creating an item under an unknown category is a 404")
        void refusesUnknownCategory() {
            UUID ghost = UUID.randomUUID();
            when(categories.findById(ghost)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> menuService.createItem(new MenuDtos.MenuItemRequest(
                    ghost, "Orphan", null, new BigDecimal("5.00"), 10, null)))
                    .isInstanceOf(ApiExceptions.NotFoundException.class);
        }

        @Test
        @DisplayName("toggling availability publishes the change")
        void togglesAvailability() {
            MenuItem item = new MenuItem(mains, "Chocolate Tart", null,
                    new BigDecimal("6.00"), 5, null);
            when(items.findWithCategoryById(item.getId())).thenReturn(Optional.of(item));

            var updated = menuService.setAvailability(item.getId(), false);

            assertThat(updated.available()).isFalse();
            verify(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
        }

        @Test
        @DisplayName("availableOnly=true asks the repository for available items only")
        void filtersToAvailableItems() {
            // The customer-facing menu must not offer sold-out dishes.
            MenuItem available = new MenuItem(mains, "In Stock", null,
                    new BigDecimal("5.00"), 10, null);
            when(items.findAvailableWithCategory()).thenReturn(List.of(available));

            var listed = menuService.listItems(true);

            assertThat(listed).hasSize(1);
            assertThat(listed.getFirst().name()).isEqualTo("In Stock");
            verify(items, never()).findAllWithCategory();
        }

        @Test
        @DisplayName("an unknown item is a 404")
        void unknownItemIsNotFound() {
            UUID ghost = UUID.randomUUID();
            when(items.findWithCategoryById(ghost)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> menuService.getItem(ghost))
                    .isInstanceOf(ApiExceptions.NotFoundException.class);
        }

        @Test
        @DisplayName("an item update keeps working when the broker is down")
        void survivesBrokerOutage() {
            MenuItem item = new MenuItem(mains, "Resilient", null,
                    new BigDecimal("5.00"), 10, null);
            when(items.findWithCategoryById(item.getId())).thenReturn(Optional.of(item));
            doThrow(new AmqpException("broker down"))
                    .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

            var updated = menuService.setAvailability(item.getId(), false);

            assertThat(updated.available()).isFalse();
        }
    }

    @Nested
    @DisplayName("pricing lookup")
    class Pricing {

        @Test
        @DisplayName("returns name, price and availability for the ids it finds")
        void pricesKnownItems() {
            MenuItem a = new MenuItem(mains, "Tilapia", null, new BigDecimal("12.50"), 25, null);
            MenuItem b = new MenuItem(mains, "Brochette", null, new BigDecimal("11.00"), 20, null);
            when(items.findByIdIn(List.of(a.getId(), b.getId()))).thenReturn(List.of(a, b));

            var priced = menuService.priceItems(List.of(a.getId(), b.getId()));

            assertThat(priced).hasSize(2);
            assertThat(priced).extracting(MenuDtos.PricedItem::price)
                    .containsExactlyInAnyOrder(new BigDecimal("12.50"), new BigDecimal("11.00"));
        }

        @Test
        @DisplayName("silently omits ids that do not exist")
        void omitsUnknownIds() {
            // order-service compares what it asked for against what came back
            // and rejects the order. Guessing a price here would be worse.
            MenuItem known = new MenuItem(mains, "Known", null, new BigDecimal("5.00"), 10, null);
            UUID ghost = UUID.randomUUID();
            when(items.findByIdIn(List.of(known.getId(), ghost))).thenReturn(List.of(known));

            var priced = menuService.priceItems(List.of(known.getId(), ghost));

            assertThat(priced).hasSize(1);
            assertThat(priced.getFirst().id()).isEqualTo(known.getId());
        }

        @Test
        @DisplayName("reports availability so a sold-out item can be refused")
        void reportsAvailability() {
            MenuItem soldOut = new MenuItem(mains, "Sold Out", null,
                    new BigDecimal("8.00"), 15, null);
            soldOut.setAvailable(false);
            when(items.findByIdIn(List.of(soldOut.getId()))).thenReturn(List.of(soldOut));

            var priced = menuService.priceItems(List.of(soldOut.getId()));

            assertThat(priced.getFirst().available()).isFalse();
        }
    }

    @Test
    @DisplayName("updating an item publishes menu.item.updated")
    void publishesOnUpdate() {
        MenuItem item = new MenuItem(mains, "Old Name", null, new BigDecimal("5.00"), 10, null);
        when(items.findWithCategoryById(item.getId())).thenReturn(Optional.of(item));
        when(categories.findById(mains.getId())).thenReturn(Optional.of(mains));

        menuService.updateItem(item.getId(), new MenuDtos.MenuItemRequest(
                mains.getId(), "New Name", "Now described", new BigDecimal("7.25"), 12, null));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(rabbit).convertAndSend(anyString(), anyString(), payload.capture());
        assertThat(payload.getValue()).hasToString(payload.getValue().toString());
        assertThat(item.getName()).isEqualTo("New Name");
        assertThat(item.getPrice()).isEqualByComparingTo("7.25");
    }
}
