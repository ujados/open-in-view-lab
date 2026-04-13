package com.example.osivlab.repository;

import com.example.osivlab.domain.OrderDetail;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderDetailRepository extends JpaRepository<OrderDetail, Long> {

    @EntityGraph(attributePaths = {"product", "product.category", "vendor", "warehouse", "warehouse.department"})
    Optional<OrderDetail> findWithAllRelationsById(Long id);
}
