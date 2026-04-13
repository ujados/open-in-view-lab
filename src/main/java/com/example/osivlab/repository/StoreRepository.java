package com.example.osivlab.repository;

import com.example.osivlab.domain.Store;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    @EntityGraph(attributePaths = {"storeType", "region", "timezone"})
    Optional<Store> findWithAllRelationsById(Long id);

    @EntityGraph(attributePaths = {"storeType", "region", "timezone"})
    List<Store> findAllWithAllRelationsBy();
}
