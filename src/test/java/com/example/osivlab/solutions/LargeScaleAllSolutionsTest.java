package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.DepartmentWithBatchSize;
import com.example.osivlab.domain.DepartmentWithSubselect;
import com.example.osivlab.dto.DepartmentDto;
import com.example.osivlab.dto.DepartmentProjection;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.dto.StoreProjection;
import com.example.osivlab.dto.StoreView;
import com.example.osivlab.repository.DepartmentWithBatchSizeRepository;
import com.example.osivlab.repository.DepartmentWithSubselectRepository;
import com.example.osivlab.repository.StoreRepository;
import com.example.osivlab.service.DepartmentService;
import com.example.osivlab.service.StoreInitializeService;
import com.example.osivlab.service.StoreJdbcService;
import com.example.osivlab.service.StoreService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Head-to-head volumetry for ALL solutions at the same scales.
 * Produces the definitive comparison table for the blog article.
 */
@ActiveProfiles({"test", "large-scale"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LargeScaleAllSolutionsTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private StoreJdbcService jdbcService;
    @Autowired private StoreInitializeService initService;
    @Autowired private StoreRepository storeRepository;
    @Autowired private DepartmentService departmentService;
    @Autowired private DepartmentWithSubselectRepository subselectRepo;
    @Autowired private DepartmentWithBatchSizeRepository batchSizeRepo;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;
    @Autowired private TransactionTemplate transactionTemplate;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    // =========================================================================
    // STORE: ALL READ SOLUTIONS (1K, 10K, 100K)
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Store: ALL solutions head-to-head (1K, 10K, 100K — shared refs)")
    void allStoresSolutions() {
        int[] volumes = {1_000, 10_000, 100_000};

        System.out.println();
        System.out.println("=== COMPARATIVA COMPLETA: Store (3 ManyToOne, refs compartidas) ===");
        System.out.println("--------------------------------------------------------------------------------------------");
        System.out.printf("  | %-10s | %-12s | %-12s | %-12s | %-12s | %-12s | %-12s |%n",
                "Records", "@EntityGraph", "DTO Project", "Interface P", "JdbcClient", "@Transact", "initialize()");
        System.out.println("--------------------------------------------------------------------------------------------");

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertStoresWithSharedRefs(vol);

            // @EntityGraph
            queryCounter.clear();
            long t1 = System.currentTimeMillis();
            List<StoreDto> eg = storeService.getAllStoresWithEntityGraph();
            long egTime = System.currentTimeMillis() - t1;
            long egQ = queryCounter.getPrepareStatementCount();
            assertThat(eg).hasSize(vol);

            // DTO Projection
            queryCounter.clear();
            long t2 = System.currentTimeMillis();
            List<StoreProjection> dto = storeService.getAllStoresProjection();
            long dtoTime = System.currentTimeMillis() - t2;
            long dtoQ = queryCounter.getPrepareStatementCount();
            assertThat(dto).hasSize(vol);

            // Interface Projection
            queryCounter.clear();
            long t3 = System.currentTimeMillis();
            List<StoreView> iface = storeRepository.findAllProjectedBy();
            long ifaceTime = System.currentTimeMillis() - t3;
            long ifaceQ = queryCounter.getPrepareStatementCount();
            assertThat(iface).hasSize(vol);

            // JdbcClient
            long t4 = System.currentTimeMillis();
            List<StoreProjection> jdbc = jdbcService.getAllStoresProjection();
            long jdbcTime = System.currentTimeMillis() - t4;
            assertThat(jdbc).hasSize(vol);

            // @Transactional
            queryCounter.clear();
            long t5 = System.currentTimeMillis();
            List<StoreDto> tx = storeService.getAllStoresTransactional();
            long txTime = System.currentTimeMillis() - t5;
            long txQ = queryCounter.getPrepareStatementCount();
            assertThat(tx).hasSize(vol);

            // Hibernate.initialize()
            queryCounter.clear();
            long t6 = System.currentTimeMillis();
            List<StoreDto> init = initService.getAllStoresWithInitialize();
            long initTime = System.currentTimeMillis() - t6;
            long initQ = queryCounter.getPrepareStatementCount();
            assertThat(init).hasSize(vol);

            System.out.printf("  | %,10d | %2dq %,6dms | %2dq %,6dms | %2dq %,6dms | 1q %,6dms | %2dq %,6dms | %2dq %,6dms |%n",
                    vol, egQ, egTime, dtoQ, dtoTime, ifaceQ, ifaceTime, jdbcTime, txQ, txTime, initQ, initTime);
        }
        System.out.println("--------------------------------------------------------------------------------------------");
        System.out.println();
    }

    // =========================================================================
    // DEPARTMENT: ALL COLLECTION SOLUTIONS (25, 100, 500, 1K, 5K depts x 5 items)
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("Department: ALL solutions head-to-head (25 → 5K depts x 5 items/col)")
    void allDepartmentSolutions() {
        int[] volumes = {25, 100, 500, 1_000};

        System.out.println();
        System.out.println("=== COMPARATIVA COMPLETA: Department (6 colecciones x 5 items) ===");
        System.out.println("--------------------------------------------------------------------------------------------");
        System.out.printf("  | %-6s | %-14s | %-14s | %-14s | %-14s |%n",
                "Depts", "@Transactional", "SUBSELECT", "Split(1dept)", "DTO Proj(1dept)");
        System.out.println("--------------------------------------------------------------------------------------------");

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertDepartmentsWithCollections(vol, 5);

            // @Transactional (findAll — N+1)
            queryCounter.clear();
            long t1 = System.currentTimeMillis();
            List<DepartmentDto> tx = departmentService.getAllDepartmentsTransactional();
            long txTime = System.currentTimeMillis() - t1;
            long txQ = queryCounter.getPrepareStatementCount();
            assertThat(tx).hasSize(vol);

            // @Fetch(SUBSELECT) (findAll — constant queries)
            queryCounter.clear();
            transactionTemplate.setReadOnly(true);
            long t2 = System.currentTimeMillis();
            long subQ = transactionTemplate.execute(status -> {
                List<DepartmentWithSubselect> depts = subselectRepo.findAll();
                depts.forEach(d -> {
                    d.getEmployees().size();
                    d.getProjects().size();
                    d.getBudgets().size();
                    d.getEquipment().size();
                    d.getPolicies().size();
                    d.getDocuments().size();
                });
                return queryCounter.getPrepareStatementCount();
            });
            long subTime = System.currentTimeMillis() - t2;
            transactionTemplate.setReadOnly(false);

            // Split Queries (single dept — id=1)
            queryCounter.clear();
            long t3 = System.currentTimeMillis();
            DepartmentDto split = departmentService.getDepartmentSplitQueries(1L);
            long splitTime = System.currentTimeMillis() - t3;
            long splitQ = queryCounter.getPrepareStatementCount();

            // DTO Projection (single dept — id=1)
            queryCounter.clear();
            long t4 = System.currentTimeMillis();
            DepartmentProjection dtoProj = departmentService.getDepartmentProjection(1L);
            long dtoProjTime = System.currentTimeMillis() - t4;
            long dtoProjQ = queryCounter.getPrepareStatementCount();

            System.out.printf("  | %,6d | %,5dq %,6dms | %,5dq %,6dms | %,5dq %,6dms | %,5dq %,6dms |%n",
                    vol, txQ, txTime, subQ, subTime, splitQ, splitTime, dtoProjQ, dtoProjTime);
        }
        System.out.println("--------------------------------------------------------------------------------------------");
        System.out.println("  @Transactional: 1 + 7N | SUBSELECT: ~8 constante | Split/DTO: 7 constante (1 dept)");
        System.out.println();
    }

    // =========================================================================
    // MEMORY COMPARISON AT SCALE
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("Memory: ALL solutions at 100K stores")
    void memoryAllSolutions() {
        testDataFactory.cleanAll();
        testDataFactory.bulkInsertStoresWithSharedRefs(100_000);
        Runtime rt = Runtime.getRuntime();

        System.out.println();
        System.out.println("=== MEMORIA: 100K stores — todas las soluciones ===");
        System.out.println("----------------------------------------------------");

        // @EntityGraph
        System.gc(); long before = rt.totalMemory() - rt.freeMemory();
        List<StoreDto> eg = storeService.getAllStoresWithEntityGraph();
        long egMem = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024;
        eg = null; System.gc();

        // DTO Projection
        before = rt.totalMemory() - rt.freeMemory();
        List<StoreProjection> dto = storeService.getAllStoresProjection();
        long dtoMem = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024;
        dto = null; System.gc();

        // Interface Projection
        before = rt.totalMemory() - rt.freeMemory();
        List<StoreView> iface = storeRepository.findAllProjectedBy();
        long ifaceMem = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024;
        iface = null; System.gc();

        // JdbcClient
        before = rt.totalMemory() - rt.freeMemory();
        List<StoreProjection> jdbc = jdbcService.getAllStoresProjection();
        long jdbcMem = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024;
        jdbc = null; System.gc();

        // @Transactional
        before = rt.totalMemory() - rt.freeMemory();
        List<StoreDto> tx = storeService.getAllStoresTransactional();
        long txMem = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024;
        tx = null; System.gc();

        // Hibernate.initialize()
        before = rt.totalMemory() - rt.freeMemory();
        List<StoreDto> init = initService.getAllStoresWithInitialize();
        long initMem = (rt.totalMemory() - rt.freeMemory() - before) / 1024 / 1024;
        init = null; System.gc();

        System.out.printf("  @EntityGraph:       %,d MB (entities + snapshots + PC)%n", egMem);
        System.out.printf("  @Transactional:     %,d MB (entities + snapshots + PC)%n", txMem);
        System.out.printf("  initialize():       %,d MB (entities + snapshots + PC)%n", initMem);
        System.out.printf("  DTO Projection:     %,d MB (records only)%n", dtoMem);
        System.out.printf("  Interface Project:  %,d MB (proxied records)%n", ifaceMem);
        System.out.printf("  JdbcClient:         %,d MB (records only, no Hibernate)%n", jdbcMem);
        System.out.println("----------------------------------------------------");
        System.out.println();
    }
}
