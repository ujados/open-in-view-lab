package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Department;
import com.example.osivlab.repository.DepartmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the pagination trap: @EntityGraph + Pageable + collection fetch
 * triggers HHH90003004 — Hibernate loads ALL rows and paginates IN MEMORY.
 *
 * With 10 departments requesting page of 3, Hibernate loads all 10 into memory
 * and returns only 3. The SQL has NO LIMIT/OFFSET clause.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class PaginationTrapTest extends AbstractIntegrationTest {

    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private TestDataFactory testDataFactory;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        testDataFactory.createDepartmentsWithCollections(10, 3);
    }

    @Test
    @DisplayName("@EntityGraph + Pageable + collection → in-memory pagination (ALL rows loaded)")
    void entityGraphWithPaginationLoadsEverything() {
        // Request page 0, size 3 — we only want 3 departments
        Page<Department> page = departmentRepository.findAllWithEmployeesBy(PageRequest.of(0, 3));

        System.out.println("=== PAGINATION TRAP (@EntityGraph + Pageable + collection) ===");
        System.out.println("  Requested: page=0, size=3");
        System.out.println("  Page content size: " + page.getContent().size());
        System.out.println("  Total elements (reported): " + page.getTotalElements());
        System.out.println("  Total pages (reported): " + page.getTotalPages());
        System.out.println("  WARNING: Hibernate loaded ALL departments into memory");
        System.out.println("  WARNING: SQL had NO LIMIT/OFFSET — check logs for HHH90003004");

        // The page reports 3 results, but Hibernate loaded all 10 into memory first
        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(10);

        // Each department's employees collection IS loaded (EntityGraph worked)
        page.getContent().forEach(dept ->
                assertThat(dept.getEmployees()).hasSize(3));
    }

    @Test
    @DisplayName("findAll(Pageable) WITHOUT @EntityGraph → proper SQL LIMIT/OFFSET")
    void normalPaginationUsesLimit() {
        // Same request but without @EntityGraph on a collection
        Page<Department> page = departmentRepository.findAll(PageRequest.of(0, 3));

        System.out.println("=== NORMAL PAGINATION (no @EntityGraph) ===");
        System.out.println("  Requested: page=0, size=3");
        System.out.println("  Page content size: " + page.getContent().size());
        System.out.println("  Total elements: " + page.getTotalElements());
        System.out.println("  SQL uses proper LIMIT/OFFSET");

        assertThat(page.getContent()).hasSize(3);
        assertThat(page.getTotalElements()).isEqualTo(10);
    }
}
