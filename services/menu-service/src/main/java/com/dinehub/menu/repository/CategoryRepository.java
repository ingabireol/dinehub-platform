package com.dinehub.menu.repository;

import com.dinehub.menu.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data creates a bean for each repository interface it finds by scanning.
 * It does not scan interfaces nested inside another class, so these are
 * top-level — a nested one compiles and tests fine against a mock and then fails
 * at startup with "no qualifying bean".
 */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    List<Category> findAllByOrderByDisplayOrderAscNameAsc();

    boolean existsByNameIgnoreCase(String name);
}
