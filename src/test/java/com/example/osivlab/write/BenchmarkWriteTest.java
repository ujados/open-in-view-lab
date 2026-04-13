package com.example.osivlab.write;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.service.BulkStatelessService;
import com.example.osivlab.service.DepartmentWriteService;
import com.example.osivlab.solutions.BenchmarkHelper;
import com.example.osivlab.solutions.BenchmarkHelper.Result;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

/**
 * Benchmark: ALL write solutions with warmup (2) + 5 measured runs + median.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BenchmarkWriteTest {

    private static final int WARMUP = 2;
    private static final int RUNS = 5;

    @Nested
    @ActiveProfiles({"test", "write-no-batch"})
    @DisplayName("Write benchmark: sin jdbc.batch_size")
    class WithoutBatch extends AbstractIntegrationTest {

        @Autowired private DepartmentWriteService writeService;
        @Autowired private BulkStatelessService statelessService;
        @Autowired private TestDataFactory testDataFactory;

        @Test
        @Order(1)
        @DisplayName("Cascade persist SIN batch: 10, 50, 100, 500 items/col")
        void cascadeNoBatch() {
            int[] sizes = {10, 50, 100, 500};

            System.out.println();
            System.out.println("=== BENCHMARK ESCRITURA: Cascade Persist SIN batch — warmup=" + WARMUP + " runs=" + RUNS + " ===");
            System.out.println("----------------------------------------------------------------------");
            System.out.printf("  | %-14s | %-10s | %-30s |%n", "Items/col", "Total items", "Tiempo (mediana)");
            System.out.println("----------------------------------------------------------------------");

            for (int size : sizes) {
                Result r = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                    testDataFactory.cleanAll();
                    writeService.createDepartmentWithCascade(size);
                    return size * 6;
                });
                System.out.printf("  | %14d | %10d | %30s |%n", size, size * 6, r);
            }
            System.out.println("----------------------------------------------------------------------");
            System.out.println();
        }

        @Test
        @Order(2)
        @DisplayName("Bulk insert SIN batch: 100, 1K, 5K orders")
        void bulkNoBatch() {
            int[] volumes = {100, 1_000, 5_000};

            System.out.println();
            System.out.println("=== BENCHMARK ESCRITURA: Bulk Insert SIN batch — warmup=" + WARMUP + " runs=" + RUNS + " ===");
            System.out.println("----------------------------------------------------------------------");
            System.out.printf("  | %-10s | %-30s |%n", "Orders", "Tiempo (mediana)");
            System.out.println("----------------------------------------------------------------------");

            for (int vol : volumes) {
                Result r = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                    testDataFactory.cleanAll();
                    writeService.bulkInsertOrders(vol);
                    return vol;
                });
                System.out.printf("  | %,10d | %30s |%n", vol, r);
            }
            System.out.println("----------------------------------------------------------------------");
            System.out.println();
        }

        @Test
        @Order(3)
        @DisplayName("StatelessSession vs Session SIN batch: 100, 1K, 5K orders")
        void statelessVsSession() {
            int[] volumes = {100, 1_000, 5_000};

            System.out.println();
            System.out.println("=== BENCHMARK ESCRITURA: StatelessSession vs Session — warmup=" + WARMUP + " runs=" + RUNS + " ===");
            System.out.println("--------------------------------------------------------------------------");
            System.out.printf("  | %-10s | %-26s | %-26s |%n", "Orders", "StatelessSession", "Session (flush+clear/50)");
            System.out.println("--------------------------------------------------------------------------");

            for (int vol : volumes) {
                Result statelessR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                    testDataFactory.cleanAll();
                    statelessService.bulkInsertOrdersStateless(vol);
                    return vol;
                });

                Result sessionR = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                    testDataFactory.cleanAll();
                    statelessService.bulkInsertOrdersSession(vol);
                    return vol;
                });

                System.out.printf("  | %,10d | %26s | %26s |%n", vol, statelessR, sessionR);
            }
            System.out.println("--------------------------------------------------------------------------");
            System.out.println();
        }
    }

    @Nested
    @ActiveProfiles({"test", "write-batch"})
    @DisplayName("Write benchmark: con jdbc.batch_size=50")
    class WithBatch extends AbstractIntegrationTest {

        @Autowired private DepartmentWriteService writeService;
        @Autowired private TestDataFactory testDataFactory;

        @Test
        @Order(1)
        @DisplayName("Cascade persist CON batch=50: 10, 50, 100, 500 items/col")
        void cascadeWithBatch() {
            int[] sizes = {10, 50, 100, 500};

            System.out.println();
            System.out.println("=== BENCHMARK ESCRITURA: Cascade Persist CON batch=50 — warmup=" + WARMUP + " runs=" + RUNS + " ===");
            System.out.println("----------------------------------------------------------------------");
            System.out.printf("  | %-14s | %-10s | %-30s |%n", "Items/col", "Total items", "Tiempo (mediana)");
            System.out.println("----------------------------------------------------------------------");

            for (int size : sizes) {
                Result r = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                    testDataFactory.cleanAll();
                    writeService.createDepartmentWithCascade(size);
                    return size * 6;
                });
                System.out.printf("  | %14d | %10d | %30s |%n", size, size * 6, r);
            }
            System.out.println("----------------------------------------------------------------------");
            System.out.println();
        }

        @Test
        @Order(2)
        @DisplayName("Bulk insert CON batch=50: 100, 1K, 5K orders")
        void bulkWithBatch() {
            int[] volumes = {100, 1_000, 5_000};

            System.out.println();
            System.out.println("=== BENCHMARK ESCRITURA: Bulk Insert CON batch=50 — warmup=" + WARMUP + " runs=" + RUNS + " ===");
            System.out.println("----------------------------------------------------------------------");
            System.out.printf("  | %-10s | %-30s |%n", "Orders", "Tiempo (mediana)");
            System.out.println("----------------------------------------------------------------------");

            for (int vol : volumes) {
                Result r = BenchmarkHelper.measure(WARMUP, RUNS, () -> {
                    testDataFactory.cleanAll();
                    writeService.bulkInsertOrders(vol);
                    return vol;
                });
                System.out.printf("  | %,10d | %30s |%n", vol, r);
            }
            System.out.println("----------------------------------------------------------------------");
            System.out.println();
        }
    }
}
