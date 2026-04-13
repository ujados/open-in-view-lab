package com.example.osivlab.service;

import com.example.osivlab.domain.Department;
import com.example.osivlab.dto.DepartmentDto;
import com.example.osivlab.dto.DepartmentProjection;
import com.example.osivlab.mapper.DepartmentMapper;
import com.example.osivlab.repository.DepartmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DepartmentMapper departmentMapper;
    private final EntityManager entityManager;

    /**
     * No @Transactional — relies on OSIV for lazy loading all 6 collections.
     */
    public DepartmentDto getDepartment(Long id) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Department not found: " + id));
        return departmentMapper.toDto(dept);
    }

    /**
     * @Transactional(readOnly=true) — N+1 but within a single transaction.
     */
    @Transactional(readOnly = true)
    public DepartmentDto getDepartmentTransactional(Long id) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Department not found: " + id));
        return departmentMapper.toDto(dept);
    }

    /**
     * @EntityGraph with all collections — CARTESIAN PRODUCT!
     * Demonstrates why @EntityGraph is dangerous with multiple collections.
     */
    public DepartmentDto getDepartmentWithEntityGraph(Long id) {
        Department dept = departmentRepository.findWithAllCollectionsById(id)
                .orElseThrow(() -> new RuntimeException("Department not found: " + id));
        return departmentMapper.toDto(dept);
    }

    /**
     * Split queries pattern (Vlad Mihalcea style).
     * One JOIN FETCH per collection, Hibernate L1 cache merges them.
     */
    @Transactional(readOnly = true)
    public DepartmentDto getDepartmentSplitQueries(Long id) {
        Department dept = departmentRepository.findWithEmployeesById(id)
                .orElseThrow(() -> new RuntimeException("Department not found: " + id));
        departmentRepository.findWithProjectsById(id);
        departmentRepository.findWithBudgetsById(id);
        departmentRepository.findWithEquipmentById(id);
        departmentRepository.findWithPoliciesById(id);
        departmentRepository.findWithDocumentsById(id);
        // dept now has all collections loaded via L1 cache merge
        return departmentMapper.toDto(dept);
    }

    @Transactional(readOnly = true)
    public List<DepartmentDto> getAllDepartmentsTransactional() {
        return departmentRepository.findAll().stream()
                .map(departmentMapper::toDto)
                .toList();
    }

    /**
     * DTO Projection for a single Department.
     * 7 flat queries: 1 base + 6 collection name lists. No entities loaded.
     */
    @Transactional(readOnly = true)
    public DepartmentProjection getDepartmentProjection(Long id) {
        Tuple base = (Tuple) entityManager.createNativeQuery(
                        "SELECT d.id, d.name, d.code, r.name as region_name " +
                        "FROM departments d LEFT JOIN regions r ON d.region_id = r.id WHERE d.id = :id", Tuple.class)
                .setParameter("id", id).getSingleResult();

        List<String> employees = entityManager.createQuery(
                "SELECT e.name FROM Employee e WHERE e.department.id = :id", String.class)
                .setParameter("id", id).getResultList();
        List<String> projects = entityManager.createQuery(
                "SELECT p.name FROM Project p WHERE p.department.id = :id", String.class)
                .setParameter("id", id).getResultList();
        List<String> budgets = entityManager.createQuery(
                "SELECT b.name FROM Budget b WHERE b.department.id = :id", String.class)
                .setParameter("id", id).getResultList();
        List<String> equipment = entityManager.createQuery(
                "SELECT e.name FROM Equipment e WHERE e.department.id = :id", String.class)
                .setParameter("id", id).getResultList();
        List<String> policies = entityManager.createQuery(
                "SELECT p.title FROM Policy p WHERE p.department.id = :id", String.class)
                .setParameter("id", id).getResultList();
        List<String> documents = entityManager.createQuery(
                "SELECT d.title FROM Document d WHERE d.department.id = :id", String.class)
                .setParameter("id", id).getResultList();

        return new DepartmentProjection(
                ((Number) base.get(0)).longValue(),
                (String) base.get(1),
                (String) base.get(2),
                (String) base.get(3),
                employees, projects, budgets, equipment, policies, documents
        );
    }
}
