package com.dinehub.menu.repository;

import com.dinehub.menu.entity.MenuItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

    /**
     * The category is fetched in the same query. Without the entity graph this
     * is the classic N+1: one query for the items, then one per item for its
     * category — invisible with six items, crippling with six hundred.
     */
    @EntityGraph(attributePaths = "category")
    @Query("SELECT i FROM MenuItem i ORDER BY i.category.displayOrder, i.name")
    List<MenuItem> findAllWithCategory();

    @EntityGraph(attributePaths = "category")
    @Query("SELECT i FROM MenuItem i WHERE i.available = true "
            + "ORDER BY i.category.displayOrder, i.name")
    List<MenuItem> findAvailableWithCategory();

    @EntityGraph(attributePaths = "category")
    List<MenuItem> findByCategoryId(UUID categoryId);

    @EntityGraph(attributePaths = "category")
    Optional<MenuItem> findWithCategoryById(UUID id);

    boolean existsByCategoryId(UUID categoryId);

    List<MenuItem> findByIdIn(List<UUID> ids);
}
