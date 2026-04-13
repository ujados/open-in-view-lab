package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.dto.StoreProjection;
import com.example.osivlab.service.StoreJdbcService;
import com.example.osivlab.service.StoreService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

/**
 * Measures actual memory consumption of different solutions.
 * Loads 100K stores and compares heap usage between:
 * - @EntityGraph (full entities + persistence context + snapshots)
 * - JPA DTO Projection (records, no PC)
 * - JdbcClient (records, no Hibernate at all)
 */
@ActiveProfiles({"test", "large-scale"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MemoryFootprintTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private StoreJdbcService jdbcService;
    @Autowired private TestDataFactory testDataFactory;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        testDataFactory.bulkInsertStoresWithSharedRefs(100_000);
    }

    @Test
    @Order(1)
    @DisplayName("Memory: @EntityGraph vs DTO Projection vs JdbcClient (100K stores)")
    void memoryComparison() {
        Runtime rt = Runtime.getRuntime();

        // @EntityGraph — full entities + persistence context
        System.gc();
        long beforeEg = rt.totalMemory() - rt.freeMemory();
        List<StoreDto> egResult = storeService.getAllStoresWithEntityGraph();
        long afterEg = rt.totalMemory() - rt.freeMemory();
        long egMemory = afterEg - beforeEg;
        egResult = null; // release
        System.gc();

        // JPA DTO Projection — records only
        long beforeDto = rt.totalMemory() - rt.freeMemory();
        List<StoreProjection> dtoResult = storeService.getAllStoresProjection();
        long afterDto = rt.totalMemory() - rt.freeMemory();
        long dtoMemory = afterDto - beforeDto;
        dtoResult = null;
        System.gc();

        // JdbcClient — records only, no Hibernate
        long beforeJdbc = rt.totalMemory() - rt.freeMemory();
        List<StoreProjection> jdbcResult = jdbcService.getAllStoresProjection();
        long afterJdbc = rt.totalMemory() - rt.freeMemory();
        long jdbcMemory = afterJdbc - beforeJdbc;

        System.out.println();
        System.out.println("=== MEMORIA: 100K stores — @EntityGraph vs DTO vs JDBC ===");
        System.out.println("----------------------------------------------------------------");
        System.out.printf("  @EntityGraph:    %,d MB (entities + snapshots + PC)%n", egMemory / 1024 / 1024);
        System.out.printf("  DTO Projection:  %,d MB (records only)%n", dtoMemory / 1024 / 1024);
        System.out.printf("  JdbcClient:      %,d MB (records only, no Hibernate)%n", jdbcMemory / 1024 / 1024);
        System.out.println("----------------------------------------------------------------");
        System.out.println("  @EntityGraph carga entidades + snapshots para dirty checking");
        System.out.println("  DTO y JDBC solo crean records planos sin overhead de JPA");
        System.out.println();
    }
}
