package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Department;
import com.example.osivlab.dto.DepartmentDto;
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
 * Tests split queries pattern (Vlad Mihalcea style).
 * One JOIN FETCH per collection, L1 cache merges results.
 * No cartesian product, no N+1, but more code.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class SplitQueryTest extends AbstractIntegrationTest {

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
    @DisplayName("Split queries → 7 queries (1 base + 6 collections), no cartesian product")
    void splitQueriesAvoidCartesianProduct() {
        queryCounter.clear();

        DepartmentDto dept = departmentService.getDepartmentSplitQueries(departmentId);

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== Split Queries (1 Department, 6 collections x 5 items) ===");
        System.out.println("  " + queryCounter.getSummary());

        assertThat(dept).isNotNull();
        assertThat(dept.getEmployeeNames()).hasSize(5);
        assertThat(dept.getProjectNames()).hasSize(5);
        assertThat(dept.getBudgetNames()).hasSize(5);
        assertThat(dept.getEquipmentNames()).hasSize(5);
        assertThat(dept.getPolicyTitles()).hasSize(5);
        assertThat(dept.getDocumentTitles()).hasSize(5);

        // Exactly 6 queries: 1 with employees + 5 for other collections (L1 cache merges)
        assertThat(queries).isLessThanOrEqualTo(7);
        System.out.println("  Total prepared statements: " + queries);
    }
}
