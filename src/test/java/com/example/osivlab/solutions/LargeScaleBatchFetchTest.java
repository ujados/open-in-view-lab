package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreDto;
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
 * Large-scale volumetry with batch_fetch_size=16.
 * Tests 1K → 1M stores. Uses PostgreSQL generate_series for instant seeding.
 */
@ActiveProfiles({"test", "large-scale-batch"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LargeScaleBatchFetchTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    @Test
    @Order(1)
    @DisplayName("batch_fetch=16 (refs unicas): 1K → 100K")
    void batchFetchLargeScaleUniqueRefs() {
        int[] volumes = {1_000, 10_000, 100_000};
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
                long withoutBatch = 1 + (long) vol * 3;
                rows.add(String.format("  | %,10d | %,8d | %,8d ms | sin batch: %,d (%.0fx reduccion)",
                        vol, queries, elapsed, withoutBatch, (double) withoutBatch / queries));
            } catch (OutOfMemoryError | Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                rows.add(String.format("  | %,10d |      --- | %,8d ms | FAILED: %s", vol, elapsed, e.getClass().getSimpleName()));
            }
        }

        System.out.println();
        System.out.println("=== GRAN ESCALA: batch_fetch_size=16 (refs UNICAS) ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Registros  | Queries  | Tiempo     | Comparativa");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  Formula: 1 + ceil(N/16)*3");
        System.out.println();
    }

    @Test
    @Order(2)
    @DisplayName("batch_fetch=16 (refs compartidas): 1K → 1M")
    void batchFetchLargeScaleSharedRefs() {
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
        System.out.println("=== GRAN ESCALA: batch_fetch_size=16 (refs COMPARTIDAS) ===");
        System.out.println("---------------------------------------------------------------------");
        System.out.println("  | Registros  | Queries | Tiempo     | Resultado");
        System.out.println("---------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("---------------------------------------------------------------------");
        System.out.println();
    }
}
