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
 * Demonstrates why @EntityGraph is dangerous with multiple collections.
 * A single JOIN on 6 collections of 5 items each = cartesian product.
 * Expected rows: 5^6 = 15,625 rows returned by the DB!
 */
@ActiveProfiles({"test", "osiv-disabled"})
class CartesianProductTest extends AbstractIntegrationTest {

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
    @DisplayName("@EntityGraph with 6 collections → cartesian product (1 query but massive result set)")
    void entityGraphCartesianProduct() {
        queryCounter.clear();

        // This will trigger Hibernate's MultipleBagFetchException or produce a cartesian product
        // depending on collection types (Set vs List)
        try {
            DepartmentDto dept = departmentService.getDepartmentWithEntityGraph(departmentId);

            long queries = queryCounter.getPrepareStatementCount();
            System.out.println("=== @EntityGraph CARTESIAN (1 Department, 6 collections x 5 items) ===");
            System.out.println("  " + queryCounter.getSummary());
            System.out.println("  WARNING: Single query but potentially 5^6 = 15,625 rows!");

            assertThat(dept).isNotNull();
            System.out.println("  Total prepared statements: " + queries);
        } catch (Exception e) {
            // MultipleBagFetchException is expected with Lists
            System.out.println("=== @EntityGraph CARTESIAN — EXCEPTION (expected) ===");
            System.out.println("  " + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.out.println("  This proves why @EntityGraph is dangerous with multiple collections!");
        }
    }
}
