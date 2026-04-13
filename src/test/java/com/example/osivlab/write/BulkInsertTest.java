package com.example.osivlab.write;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.service.DepartmentWriteService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares bulk insert performance with and without jdbc.batch_size.
 * Inserts N independent Order entities.
 *
 * Without batch: N INSERTs = N JDBC round-trips.
 * With batch=50: N INSERTs grouped into ceil(N/50) JDBC batches.
 */
class BulkInsertTest {

    @Nested
    @ActiveProfiles({"test", "write-no-batch"})
    @DisplayName("BULK INSERT: sin jdbc.batch_size")
    class WithoutBatch extends AbstractIntegrationTest {

        @Autowired private DepartmentWriteService writeService;
        @Autowired private TestDataFactory testDataFactory;
        @Autowired private EntityManagerFactory emf;

        private QueryCountUtil queryCounter;

        @BeforeEach
        void setUp() {
            testDataFactory.cleanAll();
            queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
        }

        @Test
        @DisplayName("Bulk insert: 100, 1K, 5K orders SIN batch")
        void bulkWithoutBatch() {
            int[] volumes = {100, 1_000, 5_000};
            List<String> rows = new ArrayList<>();

            for (int vol : volumes) {
                testDataFactory.cleanAll();
                queryCounter.clear();
                long start = System.currentTimeMillis();

                writeService.bulkInsertOrders(vol);
                long elapsed = System.currentTimeMillis() - start;
                long statements = queryCounter.getPrepareStatementCount();

                rows.add(String.format("  | %,6d orders | %,6d stmts | %,8d ms |",
                        vol, statements, elapsed));
            }

            System.out.println();
            System.out.println("=== ESCRITURA: Bulk Insert SIN jdbc.batch_size ===");
            System.out.println("-----------------------------------------------------");
            System.out.println("  | Volumen      | Statements | Tiempo     |");
            System.out.println("-----------------------------------------------------");
            rows.forEach(System.out::println);
            System.out.println("-----------------------------------------------------");
            System.out.println();
        }
    }

    @Nested
    @ActiveProfiles({"test", "write-batch"})
    @DisplayName("BULK INSERT: con jdbc.batch_size=50")
    class WithBatch extends AbstractIntegrationTest {

        @Autowired private DepartmentWriteService writeService;
        @Autowired private TestDataFactory testDataFactory;
        @Autowired private EntityManagerFactory emf;

        private QueryCountUtil queryCounter;

        @BeforeEach
        void setUp() {
            testDataFactory.cleanAll();
            queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
        }

        @Test
        @DisplayName("Bulk insert: 100, 1K, 5K orders CON batch=50")
        void bulkWithBatch() {
            int[] volumes = {100, 1_000, 5_000};
            List<String> rows = new ArrayList<>();

            for (int vol : volumes) {
                testDataFactory.cleanAll();
                queryCounter.clear();
                long start = System.currentTimeMillis();

                writeService.bulkInsertOrders(vol);
                long elapsed = System.currentTimeMillis() - start;
                long statements = queryCounter.getPrepareStatementCount();

                rows.add(String.format("  | %,6d orders | %,6d stmts | %,8d ms |",
                        vol, statements, elapsed));
            }

            System.out.println();
            System.out.println("=== ESCRITURA: Bulk Insert CON jdbc.batch_size=50 ===");
            System.out.println("-----------------------------------------------------");
            System.out.println("  | Volumen      | Statements | Tiempo     |");
            System.out.println("-----------------------------------------------------");
            rows.forEach(System.out::println);
            System.out.println("-----------------------------------------------------");
            System.out.println();
        }
    }
}
