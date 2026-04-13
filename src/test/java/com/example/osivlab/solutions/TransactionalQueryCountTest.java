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
 * Tests @Transactional(readOnly=true) solution — measures N+1 queries.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class TransactionalQueryCountTest extends AbstractIntegrationTest {

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
    @DisplayName("@Transactional → N+1 queries for 10 stores (1 + 3*10 = 31 queries)")
    void transactionalNPlusOneStores() {
        testDataFactory.createStores(10);
        queryCounter.clear();

        List<StoreDto> stores = storeService.getAllStoresTransactional();

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== @Transactional (10 Stores, 3 ManyToOne) ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Stores returned: " + stores.size());

        assertThat(stores).hasSize(10);
        // 1 query for stores + up to 3*10 lazy loads for relations (N+1)
        assertThat(queries).isGreaterThan(1);
        System.out.println("  Total prepared statements: " + queries);
    }

    @Test
    @DisplayName("@Transactional → N+1 for Department with 6 collections (many queries)")
    void transactionalNPlusOneDepartment() {
        Department created = testDataFactory.createDepartmentWithCollections(5);
        queryCounter.clear();

        DepartmentDto dept = departmentService.getDepartmentTransactional(created.getId());

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== @Transactional (1 Department, 6 collections x 5 items) ===");
        System.out.println("  " + queryCounter.getSummary());

        assertThat(dept).isNotNull();
        assertThat(dept.getEmployeeNames()).hasSize(5);
        System.out.println("  Total prepared statements: " + queries);
    }
}
