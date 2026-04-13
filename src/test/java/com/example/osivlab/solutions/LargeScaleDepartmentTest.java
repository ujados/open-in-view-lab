package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.DepartmentDto;
import com.example.osivlab.dto.DepartmentProjection;
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
 * Large-scale Department volumetry.
 * Tests N departments x 5 items/collection with all applicable solutions.
 * Uses generate_series for instant seeding.
 */
@ActiveProfiles({"test", "large-scale"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LargeScaleDepartmentTest extends AbstractIntegrationTest {

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
    @DisplayName("@Transactional: N departments (25, 100, 500, 1K, 5K)")
    void transactionalLargeScale() {
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
                rows.add(String.format("  | %,6d | %,8d | %,8d ms | OK (%,d depts, %,d total items)",
                        vol, queries, elapsed, depts.size(), (long) vol * 5 * 6));
            } catch (OutOfMemoryError | Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,6d |      --- | %,8d ms | FAILED: %s",
                        vol, elapsed, e.getClass().getSimpleName()));
            }
        }

        System.out.println();
        System.out.println("=== GRAN ESCALA DEPT: @Transactional (sin batch) x 5 items/col ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Depts  | Queries  | Tiempo     | Resultado");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  Formula: 1 + N*region + N*6 colecciones = 1 + 7N");
        System.out.println();
    }

    @Test
    @Order(2)
    @DisplayName("Split Queries: single department, varying collection sizes (5, 50, 500, 5K)")
    void splitQueriesCollectionScale() {
        int[] itemsPerCol = {5, 50, 500, 5_000};
        List<String> rows = new ArrayList<>();

        for (int items : itemsPerCol) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertDepartmentsWithCollections(1, items);

            queryCounter.clear();
            long start = System.currentTimeMillis();
            try {
                DepartmentDto dept = departmentService.getDepartmentSplitQueries(1L);
                long elapsed = System.currentTimeMillis() - start;
                long queries = queryCounter.getPrepareStatementCount();
                rows.add(String.format("  | %,6d | %,8d | %,8d ms | OK (%d items total)",
                        items, queries, elapsed, items * 6));
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,6d |      --- | %,8d ms | FAILED: %s",
                        items, elapsed, e.getClass().getSimpleName()));
            }
        }

        System.out.println();
        System.out.println("=== GRAN ESCALA DEPT: Split Queries (1 dept, variando items/col) ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Items/col | Queries  | Tiempo     | Resultado");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  Queries constantes: 6 JOIN FETCH + merges L1 cache");
        System.out.println();
    }

    @Test
    @Order(3)
    @DisplayName("DTO Projection: single department, varying collection sizes (5, 50, 500, 5K)")
    void dtoProjectionCollectionScale() {
        int[] itemsPerCol = {5, 50, 500, 5_000};
        List<String> rows = new ArrayList<>();

        for (int items : itemsPerCol) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertDepartmentsWithCollections(1, items);

            queryCounter.clear();
            long start = System.currentTimeMillis();
            try {
                DepartmentProjection dept = departmentService.getDepartmentProjection(1L);
                long elapsed = System.currentTimeMillis() - start;
                long queries = queryCounter.getPrepareStatementCount();
                rows.add(String.format("  | %,6d | %,8d | %,8d ms | OK (%d items total)",
                        items, queries, elapsed, items * 6));
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,6d |      --- | %,8d ms | FAILED: %s",
                        items, elapsed, e.getClass().getSimpleName()));
            }
        }

        System.out.println();
        System.out.println("=== GRAN ESCALA DEPT: DTO Projection (1 dept, variando items/col) ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Items/col | Queries  | Tiempo     | Resultado");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  Queries constantes: 1 base + 6 JPQL selects = 7");
        System.out.println();
    }
}
