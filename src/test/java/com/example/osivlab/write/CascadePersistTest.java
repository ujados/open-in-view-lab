package com.example.osivlab.write;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Department;
import com.example.osivlab.service.DepartmentWriteService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures the cost of cascade persist (saving a Department with 6 collections).
 * Compares: without jdbc.batch_size vs with jdbc.batch_size=50.
 *
 * Without batch: each child entity = 1 INSERT statement = 1 JDBC round-trip.
 * With batch: Hibernate groups up to 50 INSERTs into a single JDBC batch.
 */
class CascadePersistTest {

    @Nested
    @ActiveProfiles({"test", "write-no-batch"})
    @DisplayName("CASCADE PERSIST: sin jdbc.batch_size")
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
        @DisplayName("Cascade persist: 10, 50, 100, 500 items/col SIN batch")
        void cascadeWithoutBatch() {
            int[] sizes = {10, 50, 100, 500};
            List<String> rows = new ArrayList<>();

            for (int size : sizes) {
                testDataFactory.cleanAll();
                queryCounter.clear();
                long start = System.currentTimeMillis();

                Department dept = writeService.createDepartmentWithCascade(size);
                long elapsed = System.currentTimeMillis() - start;
                long statements = queryCounter.getPrepareStatementCount();
                int totalItems = size * 6;

                rows.add(String.format("  | %4d items/col | %,6d stmts | %,8d ms | %d items total",
                        size, statements, elapsed, totalItems));

                assertThat(dept.getId()).isNotNull();
            }

            System.out.println();
            System.out.println("=== ESCRITURA: Cascade Persist SIN jdbc.batch_size ===");
            System.out.println("---------------------------------------------------------------------");
            System.out.println("  | Tamano        | Statements | Tiempo     | Items");
            System.out.println("---------------------------------------------------------------------");
            rows.forEach(System.out::println);
            System.out.println("---------------------------------------------------------------------");
            System.out.println("  Formula: 1 region + 1 dept + N*6 items = 2 + 6N statements");
            System.out.println();
        }
    }

    @Nested
    @ActiveProfiles({"test", "write-batch"})
    @DisplayName("CASCADE PERSIST: con jdbc.batch_size=50")
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
        @DisplayName("Cascade persist: 10, 50, 100, 500 items/col CON batch=50")
        void cascadeWithBatch() {
            int[] sizes = {10, 50, 100, 500};
            List<String> rows = new ArrayList<>();

            for (int size : sizes) {
                testDataFactory.cleanAll();
                queryCounter.clear();
                long start = System.currentTimeMillis();

                Department dept = writeService.createDepartmentWithCascade(size);
                long elapsed = System.currentTimeMillis() - start;
                long statements = queryCounter.getPrepareStatementCount();
                int totalItems = size * 6;
                long withoutBatch = 2 + (long) size * 6;

                rows.add(String.format("  | %4d items/col | %,6d stmts | %,8d ms | sin batch: %,d",
                        size, statements, elapsed, withoutBatch));

                assertThat(dept.getId()).isNotNull();
            }

            System.out.println();
            System.out.println("=== ESCRITURA: Cascade Persist CON jdbc.batch_size=50 ===");
            System.out.println("---------------------------------------------------------------------");
            System.out.println("  | Tamano        | Statements | Tiempo     | Comparativa");
            System.out.println("---------------------------------------------------------------------");
            rows.forEach(System.out::println);
            System.out.println("---------------------------------------------------------------------");
            System.out.println("  jdbc.batch_size agrupa INSERTs en batches JDBC");
            System.out.println();
        }
    }
}
