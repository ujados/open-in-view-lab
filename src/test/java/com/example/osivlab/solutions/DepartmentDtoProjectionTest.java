package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Department;
import com.example.osivlab.dto.DepartmentProjection;
import com.example.osivlab.service.DepartmentService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DTO Projection for Department with 6 collections.
 * Uses 7 flat queries (1 base + 6 collection name lists).
 * No entities loaded, no persistence context.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class DepartmentDtoProjectionTest extends AbstractIntegrationTest {

    @Autowired private DepartmentService departmentService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;
    private Long departmentId;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
        Department created = testDataFactory.createDepartmentWithCollections(5);
        departmentId = created.getId();
    }

    @Test
    @DisplayName("DTO Projection → 7 queries (1 base + 6 collections), 0 entities loaded")
    void dtoProjectionDepartment() {
        queryCounter.clear();

        DepartmentProjection dept = departmentService.getDepartmentProjection(departmentId);

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== DTO Projection (1 Department, 6 collections x 5 items) ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Total prepared statements: " + queries);

        assertThat(dept).isNotNull();
        assertThat(dept.name()).isEqualTo("Engineering");
        assertThat(dept.regionName()).isEqualTo("Central");
        assertThat(dept.employeeNames()).hasSize(5);
        assertThat(dept.projectNames()).hasSize(5);
        assertThat(dept.budgetNames()).hasSize(5);
        assertThat(dept.equipmentNames()).hasSize(5);
        assertThat(dept.policyTitles()).hasSize(5);
        assertThat(dept.documentTitles()).hasSize(5);

        // 7 queries: 1 base (native) + 6 JPQL selects for collection names
        assertThat(queries).isEqualTo(7);
    }
}
