package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.DepartmentWithBatchSize;
import com.example.osivlab.domain.DepartmentWithSubselect;
import com.example.osivlab.dto.DepartmentDto;
import com.example.osivlab.dto.DepartmentProjection;
import com.example.osivlab.repository.DepartmentWithBatchSizeRepository;
import com.example.osivlab.repository.DepartmentWithSubselectRepository;
import com.example.osivlab.service.DepartmentService;
import com.example.osivlab.solutions.BenchmarkHelper.Result;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Benchmark: ALL Department solutions with warmup (2) + 5 measured runs + median.
 * Tests findAll at 25, 100, 500, 1K, 5K departments x 5 items/collection.
 * Also tests single-entity solutions (Split, DTO Projection) at varying collection sizes.
 */
@ActiveProfiles({"test", "osiv-disabled"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BenchmarkDepartmentTest extends AbstractIntegrationTest {

    private static final int WARMUP = 2;
    private static final int RUNS = 5;

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

    @Test
    @Order(1)
    @DisplayName("Benchmark Dept findAll: SUBSELECT vs @BatchSize vs @Transactional (25 → 5K)")
    void benchmarkDeptFindAll() {
        int[] volumes = {25, 100, 500, 1_000, 5_000};

        System.out.println();
        System.out.println("=== BENCHMARK: Department findAll (6 cols x 5 items) — warmup=" + WARMUP + " runs=" + RUNS + " ===");
        System.out.println("------------------------------------------------------------------------------------");
        System.out.printf("  | %-6s | %-22s | %-22s | %-22s |%n",
                "Depts", "@Transactional (N+1)", "@BatchSize(16)", "SUBSELECT");
        System.out.println("------------------------------------------------------------------------------------");

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertDepartmentsWithCollections(vol, 5);

            // @Transactional (N+1)
            Result txR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                List<DepartmentDto> r = departmentService.getAllDepartmentsTransactional();
                assertThat(r).hasSize(vol);
                return r.size();
            });

            // @BatchSize(16)
            Result batchR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                transactionTemplate.setReadOnly(true);
                try {
                    return transactionTemplate.execute(status -> {
                        List<DepartmentWithBatchSize> r = batchSizeRepo.findAll();
                        r.forEach(d -> {
                            d.getEmployees().size(); d.getProjects().size();
                            d.getBudgets().size(); d.getEquipment().size();
                            d.getPolicies().size(); d.getDocuments().size();
                        });
                        return r.size();
                    });
                } finally {
                    transactionTemplate.setReadOnly(false);
                }
            });

            // SUBSELECT
            Result subR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                transactionTemplate.setReadOnly(true);
                try {
                    return transactionTemplate.execute(status -> {
                        List<DepartmentWithSubselect> r = subselectRepo.findAll();
                        r.forEach(d -> {
                            d.getEmployees().size(); d.getProjects().size();
                            d.getBudgets().size(); d.getEquipment().size();
                            d.getPolicies().size(); d.getDocuments().size();
                        });
                        return r.size();
                    });
                } finally {
                    transactionTemplate.setReadOnly(false);
                }
            });

            System.out.printf("  | %,6d | %22s | %22s | %22s |%n", vol, txR, batchR, subR);
        }
        System.out.println("------------------------------------------------------------------------------------");
        System.out.println("  Valores = mediana de " + RUNS + " ejecuciones tras " + WARMUP + " warmup runs");
        System.out.println();
    }

    @Test
    @Order(2)
    @DisplayName("Benchmark single Dept: Split vs DTO Projection (5 → 5K items/col)")
    void benchmarkSingleDeptSolutions() {
        int[] itemsPerCol = {5, 50, 500, 5_000};

        System.out.println();
        System.out.println("=== BENCHMARK: Single Department (varying items/col) — warmup=" + WARMUP + " runs=" + RUNS + " ===");
        System.out.println("----------------------------------------------------------------------");
        System.out.printf("  | %-10s | %-22s | %-22s |%n",
                "Items/col", "Split Queries", "DTO Projection");
        System.out.println("----------------------------------------------------------------------");

        for (int items : itemsPerCol) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertDepartmentsWithCollections(1, items);

            Result splitR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                DepartmentDto r = departmentService.getDepartmentSplitQueries(1L);
                assertThat(r).isNotNull();
                return 1;
            });

            Result dtoR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                DepartmentProjection r = departmentService.getDepartmentProjection(1L);
                assertThat(r).isNotNull();
                return 1;
            });

            System.out.printf("  | %,10d | %22s | %22s |%n", items, splitR, dtoR);
        }
        System.out.println("----------------------------------------------------------------------");
        System.out.println("  Valores = mediana de " + RUNS + " ejecuciones tras " + WARMUP + " warmup runs");
        System.out.println();
    }
}
