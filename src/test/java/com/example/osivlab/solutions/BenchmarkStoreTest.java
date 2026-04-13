package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.dto.StoreProjection;
import com.example.osivlab.dto.StoreView;
import com.example.osivlab.repository.StoreRepository;
import com.example.osivlab.service.StoreInitializeService;
import com.example.osivlab.service.StoreJdbcService;
import com.example.osivlab.service.StoreService;
import com.example.osivlab.solutions.BenchmarkHelper.Result;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmark: ALL Store solutions with warmup (2) + 5 measured runs + median.
 * Tests at 1K, 10K, 100K records with shared refs.
 */
@ActiveProfiles({"test", "large-scale"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BenchmarkStoreTest extends AbstractIntegrationTest {

    private static final int WARMUP = 2;
    private static final int RUNS = 5;

    @Autowired private StoreService storeService;
    @Autowired private StoreJdbcService jdbcService;
    @Autowired private StoreInitializeService initService;
    @Autowired private StoreRepository storeRepository;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    @Test
    @Order(1)
    @DisplayName("Benchmark Store: 6 solutions x 3 volumes, warmup=2, runs=5, median")
    void benchmarkAllStoreSolutions() {
        int[] volumes = {1_000, 10_000, 100_000};

        System.out.println();
        System.out.println("=== BENCHMARK: Store (3 ManyToOne, refs compartidas) — warmup=" + WARMUP + " runs=" + RUNS + " ===");
        System.out.println("------------------------------------------------------------------------------------------------------");
        System.out.printf("  | %-10s | %-16s | %-16s | %-16s | %-16s | %-16s | %-16s |%n",
                "Records", "@EntityGraph", "DTO Projection", "Interface Proj", "JdbcClient", "@Transactional", "initialize()");
        System.out.println("------------------------------------------------------------------------------------------------------");

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertStoresWithSharedRefs(vol);

            Result egR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                List<StoreDto> r = storeService.getAllStoresWithEntityGraph();
                assertThat(r).hasSize(vol);
                return r.size();
            });

            Result dtoR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                List<StoreProjection> r = storeService.getAllStoresProjection();
                assertThat(r).hasSize(vol);
                return r.size();
            });

            Result ifaceR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                List<StoreView> r = storeRepository.findAllProjectedBy();
                assertThat(r).hasSize(vol);
                return r.size();
            });

            Result jdbcR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                List<StoreProjection> r = jdbcService.getAllStoresProjection();
                assertThat(r).hasSize(vol);
                return r.size();
            });

            Result txR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                List<StoreDto> r = storeService.getAllStoresTransactional();
                assertThat(r).hasSize(vol);
                return r.size();
            });

            Result initR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                List<StoreDto> r = initService.getAllStoresWithInitialize();
                assertThat(r).hasSize(vol);
                return r.size();
            });

            System.out.printf("  | %,10d | %16s | %16s | %16s | %16s | %16s | %16s |%n",
                    vol, egR, dtoR, ifaceR, jdbcR, txR, initR);
        }
        System.out.println("------------------------------------------------------------------------------------------------------");
        System.out.println("  Valores = mediana de " + RUNS + " ejecuciones tras " + WARMUP + " warmup runs");
        System.out.println();
    }
}
