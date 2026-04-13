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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volumetry with batch_fetch_size=16 enabled.
 * Shows how batch fetching scales compared to raw @Transactional.
 */
@ActiveProfiles({"test", "batch-fetch"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VolumetryBatchFetchTest extends AbstractIntegrationTest {

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
    @Order(1)
    @DisplayName("batch_fetch_size=16: Store volumetry with unique refs (5, 25, 50, 100)")
    void storeBatchVolumetry() {
        int[] volumes = {5, 25, 50, 100};
        List<long[]> results = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.createStoresWithUniqueRelations(vol);
            queryCounter.clear();
            List<StoreDto> stores = storeService.getAllStoresTransactional();
            assertThat(stores).hasSize(vol);
            results.add(new long[]{vol, queryCounter.getPrepareStatementCount()});
        }

        System.out.println();
        System.out.println("=== VOLUMETRIA: Store con batch_fetch_size=16 (refs unicas) ===");
        System.out.println("  Comparar con sin batch: 5->16, 25->76, 50->151, 100->301");
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  | Registros  | Con batch_fetch=16   | Sin batch (esperado)         |");
        System.out.println("-----------------------------------------------------------------------");
        for (long[] r : results) {
            long withoutBatch = 1 + r[0] * 3; // 1 base + N*3 refs
            System.out.printf("  | %4d       | %4d                 | %4d                         |%n",
                    r[0], r[1], withoutBatch);
        }
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  Con batch: 1 + ceil(N/16)*3  |  Sin batch: 1 + N*3");
        System.out.println();
    }

    @Test
    @Order(2)
    @DisplayName("batch_fetch_size=16: N departments x 5 items/col")
    void departmentBatchVolumetry() {
        int[] deptCounts = {1, 5, 10, 25};
        List<long[]> results = new ArrayList<>();

        for (int count : deptCounts) {
            testDataFactory.cleanAll();
            testDataFactory.createDepartmentsWithCollections(count, 5);
            queryCounter.clear();
            List<DepartmentDto> depts = departmentService.getAllDepartmentsTransactional();
            assertThat(depts).hasSize(count);
            results.add(new long[]{count, queryCounter.getPrepareStatementCount()});
        }

        System.out.println();
        System.out.println("=== VOLUMETRIA: N depts x 5 items/col con batch_fetch_size=16 ===");
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  | Departamentos | Con batch=16     | Total items cargados          |");
        System.out.println("-----------------------------------------------------------------------");
        for (long[] r : results) {
            long totalItems = r[0] * 5 * 6;
            System.out.printf("  | %4d          | %4d             | %5d                         |%n",
                    r[0], r[1], totalItems);
        }
        System.out.println("-----------------------------------------------------------------------");
        System.out.println();
    }
}
