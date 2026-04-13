package com.example.osivlab.repository;

import com.example.osivlab.domain.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @EntityGraph(attributePaths = {"category"})
    Optional<Product> findWithCategoryById(Long id);

    @EntityGraph(attributePaths = {"category"})
    List<Product> findAllWithCategoryBy();
}
