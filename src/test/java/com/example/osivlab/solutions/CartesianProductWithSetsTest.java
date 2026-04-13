package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Department;
import com.example.osivlab.domain.DepartmentWithSets;
import com.example.osivlab.repository.DepartmentWithSetsRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates that changing List→Set avoids MultipleBagFetchException
 * but silently produces a CARTESIAN PRODUCT.
 *
 * With 3 Set collections of 5 items each:
 * - Expected rows from DB: 5 * 5 * 5 = 125 (for 1 department!)
 * - Hibernate deduplicates in memory, so you get the correct data
 * - But the DB transferred 125 rows instead of 15
 *
 * This is WORSE than MultipleBagFetchException because it's silent.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class CartesianProductWithSetsTest extends AbstractIntegrationTest {

    @Autowired private DepartmentWithSetsRepository departmentWithSetsRepository;
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
    @DisplayName("Set collections + @EntityGraph → 1 query but CARTESIAN PRODUCT (5^3 = 125 rows for 15 items)")
    void setsProduceCartesianProduct() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        queryCounter.clear();

        // This does NOT throw MultipleBagFetchException (unlike List)
        // But it produces a cartesian product silently
        DepartmentWithSets dept = departmentWithSetsRepository.findWithCollectionsById(departmentId)
                .orElseThrow();

        long queries = queryCounter.getPrepareStatementCount();

        System.out.println("=== CARTESIAN PRODUCT SILENCIOSO (Set collections + @EntityGraph) ===");
        System.out.println("  Queries: " + queries + " (1 JOIN query)");
        System.out.println("  Employees: " + dept.getEmployees().size());
        System.out.println("  Projects:  " + dept.getProjects().size());
        System.out.println("  Budgets:   " + dept.getBudgets().size());
        System.out.println("  Rows esperadas de la BD: 5 * 5 * 5 = 125 (cartesiano)");
        System.out.println("  Items reales: 15 (Hibernate deduplica en memoria)");
        System.out.println("  PELIGRO: la BD transfiere 125 filas para devolver 15 items");
        System.out.println("  Con 10 items/col serian: 10^3 = 1,000 filas para 30 items");
        System.out.println("  Con 50 items/col serian: 50^3 = 125,000 filas para 150 items");

        // 1 query — no MultipleBagFetchException because we use Set
        assertThat(queries).isEqualTo(1);

        // Correct data — Hibernate deduplicates
        assertThat(dept.getEmployees()).hasSize(5);
        assertThat(dept.getProjects()).hasSize(5);
        assertThat(dept.getBudgets()).hasSize(5);
    }
}
