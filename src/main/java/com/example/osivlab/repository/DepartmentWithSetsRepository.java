package com.example.osivlab.repository;

import com.example.osivlab.domain.DepartmentWithSets;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DepartmentWithSetsRepository extends JpaRepository<DepartmentWithSets, Long> {

    @EntityGraph(attributePaths = {"employees", "projects", "budgets"})
    Optional<DepartmentWithSets> findWithCollectionsById(Long id);
}
