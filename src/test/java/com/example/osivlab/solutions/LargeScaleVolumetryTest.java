package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.dto.StoreProjection;
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
 * Large-scale volumetry: 1K → 1M stores.
 * Uses PostgreSQL generate_series for instant seeding.
 * Measures query count AND execution time for each solution.
 */
@ActiveProfiles({"test", "large-scale"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LargeScaleVolumetryTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    // =========================================================================
    // @EntityGraph — always 1 query, but loads ALL entities into memory
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("@EntityGraph: 1K → 1M stores (shared refs)")
    void entityGraphLargeScale() {
        int[] volumes = {1_000, 10_000, 100_000, 500_000, 1_000_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            long seedStart = System.currentTimeMillis();
            testDataFactory.bulkInsertStoresWithSharedRefs(vol);
            long seedTime = System.currentTimeMillis() - seedStart;

            queryCounter.clear();
            long start = System.currentTimeMillis();
            try {
                List<StoreDto> stores = storeService.getAllStoresWithEntityGraph();
                long elapsed = System.currentTimeMillis() - start;
                long queries = queryCounter.getPrepareStatementCount();
                rows.add(String.format("  | %,10d | %6d | %,8d ms | %,6d ms | OK (%,d rows)",
                        vol, queries, elapsed, seedTime, stores.size()));
            } catch (OutOfMemoryError | Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,10d |    --- | %,8d ms | %,6d ms | FAILED: %s",
                        vol, elapsed, seedTime, e.getClass().getSimpleName()));
            }
        }

        System.out.println();
        System.out.println("=== GRAN ESCALA: @EntityGraph (refs compartidas) ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Registros  | Queries | Lectura    | Seeding  | Resultado");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println();
    }

    // =========================================================================
    // DTO Projection — always 1 query, no entities in memory
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("DTO Projection: 1K → 1M stores")
    void dtoProjectionLargeScale() {
        int[] volumes = {1_000, 10_000, 100_000, 500_000, 1_000_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            long seedStart = System.currentTimeMillis();
            testDataFactory.bulkInsertStoresWithSharedRefs(vol);
            long seedTime = System.currentTimeMillis() - seedStart;

            queryCounter.clear();
            long start = System.currentTimeMillis();
            try {
                List<StoreProjection> stores = storeService.getAllStoresProjection();
                long elapsed = System.currentTimeMillis() - start;
                long queries = queryCounter.getPrepareStatementCount();
                rows.add(String.format("  | %,10d | %6d | %,8d ms | %,6d ms | OK (%,d rows)",
                        vol, queries, elapsed, seedTime, stores.size()));
            } catch (OutOfMemoryError | Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,10d |    --- | %,8d ms | %,6d ms | FAILED: %s",
                        vol, elapsed, seedTime, e.getClass().getSimpleName()));
            }
        }

        System.out.println();
        System.out.println("=== GRAN ESCALA: DTO Projection ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Registros  | Queries | Lectura    | Seeding  | Resultado");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println();
    }

    // =========================================================================
    // @Transactional N+1 — unique refs: queries = 1 + N*3
    // Only up to 10K (100K would be 300K queries)
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("@Transactional N+1 (refs unicas): 1K, 10K")
    void transactionalNPlusOneLargeScale() {
        int[] volumes = {1_000, 10_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertStoresWithUniqueRefs(vol);

            queryCounter.clear();
            long start = System.currentTimeMillis();
            try {
                List<StoreDto> stores = storeService.getAllStoresTransactional();
                long elapsed = System.currentTimeMillis() - start;
                long queries = queryCounter.getPrepareStatementCount();
                rows.add(String.format("  | %,10d | %,8d | %,8d ms | OK", vol, queries, elapsed));
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,10d |      --- | %,8d ms | FAILED: %s", vol, elapsed, e.getClass().getSimpleName()));
            }
        }
        rows.add(String.format("  | %,10d | %,8d |        --- | (no ejecutado)", 100_000, 300_001));
        rows.add(String.format("  | %,10d | %,8d |        --- | (no ejecutado)", 1_000_000, 3_000_001));

        System.out.println();
        System.out.println("=== GRAN ESCALA: @Transactional N+1 (refs UNICAS) ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Registros  | Queries  | Tiempo     | Resultado");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println();
    }

    // =========================================================================
    // @Transactional — shared refs: queries stay constant
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("@Transactional (refs compartidas): 1K → 1M")
    void transactionalSharedRefsLargeScale() {
        int[] volumes = {1_000, 10_000, 100_000, 500_000, 1_000_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertStoresWithSharedRefs(vol);

            queryCounter.clear();
            long start = System.currentTimeMillis();
            try {
                List<StoreDto> stores = storeService.getAllStoresTransactional();
                long elapsed = System.currentTimeMillis() - start;
                long queries = queryCounter.getPrepareStatementCount();
                rows.add(String.format("  | %,10d | %6d | %,8d ms | OK (%,d rows)", vol, queries, elapsed, stores.size()));
            } catch (OutOfMemoryError | Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,10d |    --- | %,8d ms | FAILED: %s", vol, elapsed, e.getClass().getSimpleName()));
            }
        }

        System.out.println();
        System.out.println("=== GRAN ESCALA: @Transactional (refs COMPARTIDAS) ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Registros  | Queries | Tiempo     | Resultado");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println();
    }
}
