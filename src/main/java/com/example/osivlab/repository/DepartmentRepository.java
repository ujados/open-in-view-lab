package com.example.osivlab.repository;

import com.example.osivlab.domain.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    @EntityGraph(attributePaths = {"region", "employees", "projects", "budgets", "equipment", "policies", "documents"})
    Optional<Department> findWithAllCollectionsById(Long id);

    @EntityGraph(attributePaths = {"region", "employees", "projects", "budgets", "equipment", "policies", "documents"})
    List<Department> findAllWithAllCollectionsBy();

    @Query("SELECT d FROM Department d JOIN FETCH d.employees WHERE d.id = :id")
    Optional<Department> findWithEmployeesById(Long id);

    @Query("SELECT d FROM Department d JOIN FETCH d.projects WHERE d.id = :id")
    Optional<Department> findWithProjectsById(Long id);

    @Query("SELECT d FROM Department d JOIN FETCH d.budgets WHERE d.id = :id")
    Optional<Department> findWithBudgetsById(Long id);

    @Query("SELECT d FROM Department d JOIN FETCH d.equipment WHERE d.id = :id")
    Optional<Department> findWithEquipmentById(Long id);

    @Query("SELECT d FROM Department d JOIN FETCH d.policies WHERE d.id = :id")
    Optional<Department> findWithPoliciesById(Long id);

    @Query("SELECT d FROM Department d JOIN FETCH d.documents WHERE d.id = :id")
    Optional<Department> findWithDocumentsById(Long id);

    @EntityGraph(attributePaths = {"employees"})
    Page<Department> findAllWithEmployeesBy(Pageable pageable);
}
