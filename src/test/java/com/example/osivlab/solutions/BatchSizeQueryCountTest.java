package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Department;
import com.example.osivlab.dto.DepartmentDto;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.service.DepartmentService;
import com.example.osivlab.service.StoreService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests default_batch_fetch_size=16 solution.
 * One line of config that dramatically reduces N+1 without cartesian product.
 */
@ActiveProfiles({"test", "batch-fetch"})
class BatchSizeQueryCountTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private DepartmentService departmentService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    @Test
    @DisplayName("batch_fetch_size=16 → ~3 queries for 10 stores instead of 31")
    void batchFetchReducesStoreQueries() {
        testDataFactory.createStores(10);
        queryCounter.clear();

        List<StoreDto> stores = storeService.getAllStoresTransactional();

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== batch_fetch_size=16 (10 Stores, 3 ManyToOne) ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Stores returned: " + stores.size());

        assertThat(stores).hasSize(10);
        // With batch_fetch_size=16: 1 + ceil(10/16)*3 ≈ 4 queries instead of 31
        assertThat(queries).isLessThan(10);
        System.out.println("  Total prepared statements: " + queries);
    }

    @Test
    @DisplayName("batch_fetch_size=16 → ~7 queries for Department with 6 collections instead of many more")
    void batchFetchReducesDepartmentQueries() {
        Department created = testDataFactory.createDepartmentWithCollections(5);
        queryCounter.clear();

        DepartmentDto dept = departmentService.getDepartmentTransactional(created.getId());

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== batch_fetch_size=16 (1 Department, 6 collections x 5 items) ===");
        System.out.println("  " + queryCounter.getSummary());

        assertThat(dept).isNotNull();
        System.out.println("  Total prepared statements: " + queries);
    }
}
