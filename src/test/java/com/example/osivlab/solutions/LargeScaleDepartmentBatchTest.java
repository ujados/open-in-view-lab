package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.DepartmentDto;
import com.example.osivlab.service.DepartmentService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Large-scale Department with batch_fetch_size=16.
 */
@ActiveProfiles({"test", "large-scale-batch"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LargeScaleDepartmentBatchTest extends AbstractIntegrationTest {

    @Autowired private DepartmentService departmentService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    @Test
    @Order(1)
    @DisplayName("batch_fetch=16: N departments x 5 items/col (25, 100, 500, 1K, 5K)")
    void batchFetchDepartmentLargeScale() {
        int[] volumes = {25, 100, 500, 1_000, 5_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertDepartmentsWithCollections(vol, 5);

            queryCounter.clear();
            long start = System.currentTimeMillis();
            try {
                List<DepartmentDto> depts = departmentService.getAllDepartmentsTransactional();
                long elapsed = System.currentTimeMillis() - start;
                long queries = queryCounter.getPrepareStatementCount();
                long withoutBatch = 1 + (long) vol * 7;
                rows.add(String.format("  | %,6d | %,8d | %,8d ms | sin batch: %,d (%.0fx)",
                        vol, queries, elapsed, withoutBatch, (double) withoutBatch / queries));
            } catch (OutOfMemoryError | Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,6d |      --- | %,8d ms | FAILED: %s",
                        vol, elapsed, e.getClass().getSimpleName()));
            }
        }

        System.out.println();
        System.out.println("=== GRAN ESCALA DEPT: batch_fetch_size=16 x 5 items/col ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Depts  | Queries  | Tiempo     | Comparativa");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  Formula sin batch: 1 + 7N | Con batch: 1 + ceil(N/16)*7");
        System.out.println();
    }
}
