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

import java.util.List;

/**
 * 10 MILLION records test.
 * Only solutions that can handle this volume without OOM.
 */
@ActiveProfiles({"test", "large-scale"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenMillionTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
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
    @DisplayName("10M stores: DTO Projection (should work — no entities loaded)")
    void dtoProjection10M() {
        long seedStart = System.currentTimeMillis();
        testDataFactory.bulkInsertStoresWithSharedRefs(10_000_000);
        long seedTime = System.currentTimeMillis() - seedStart;

        queryCounter.clear();
        long start = System.currentTimeMillis();
        try {
            List<StoreProjection> stores = storeService.getAllStoresProjection();
            long elapsed = System.currentTimeMillis() - start;
            long queries = queryCounter.getPrepareStatementCount();
            System.out.println("=== 10 MILLONES: DTO Projection ===");
            System.out.printf("  Seeding: %,d ms | Queries: %d | Lectura: %,d ms | Rows: %,d%n",
                    seedTime, queries, elapsed, stores.size());
        } catch (OutOfMemoryError | Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("=== 10 MILLONES: DTO Projection ===");
            System.out.printf("  Seeding: %,d ms | FAILED after %,d ms: %s%n",
                    seedTime, elapsed, e.getClass().getSimpleName());
        }
    }

    @Test
    @Order(2)
    @DisplayName("10M stores: @EntityGraph (may OOM — loads all entities + snapshots)")
    void entityGraph10M() {
        long seedStart = System.currentTimeMillis();
        testDataFactory.bulkInsertStoresWithSharedRefs(10_000_000);
        long seedTime = System.currentTimeMillis() - seedStart;

        queryCounter.clear();
        long start = System.currentTimeMillis();
        try {
            List<StoreDto> stores = storeService.getAllStoresWithEntityGraph();
            long elapsed = System.currentTimeMillis() - start;
            long queries = queryCounter.getPrepareStatementCount();
            System.out.println("=== 10 MILLONES: @EntityGraph ===");
            System.out.printf("  Seeding: %,d ms | Queries: %d | Lectura: %,d ms | Rows: %,d%n",
                    seedTime, queries, elapsed, stores.size());
        } catch (OutOfMemoryError | Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("=== 10 MILLONES: @EntityGraph ===");
            System.out.printf("  Seeding: %,d ms | FAILED after %,d ms: %s%n",
                    seedTime, elapsed, e.getClass().getSimpleName());
        }
    }

    @Test
    @Order(3)
    @DisplayName("10M stores: @Transactional shared refs (7 queries, but 10M entities in memory)")
    void transactionalSharedRefs10M() {
        long seedStart = System.currentTimeMillis();
        testDataFactory.bulkInsertStoresWithSharedRefs(10_000_000);
        long seedTime = System.currentTimeMillis() - seedStart;

        queryCounter.clear();
        long start = System.currentTimeMillis();
        try {
            List<StoreDto> stores = storeService.getAllStoresTransactional();
            long elapsed = System.currentTimeMillis() - start;
            long queries = queryCounter.getPrepareStatementCount();
            System.out.println("=== 10 MILLONES: @Transactional (refs compartidas) ===");
            System.out.printf("  Seeding: %,d ms | Queries: %d | Lectura: %,d ms | Rows: %,d%n",
                    seedTime, queries, elapsed, stores.size());
        } catch (OutOfMemoryError | Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("=== 10 MILLONES: @Transactional (refs compartidas) ===");
            System.out.printf("  Seeding: %,d ms | FAILED after %,d ms: %s%n",
                    seedTime, elapsed, e.getClass().getSimpleName());
        }
    }
}
