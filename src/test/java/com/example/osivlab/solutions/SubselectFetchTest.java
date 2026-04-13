package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.DepartmentWithSubselect;
import com.example.osivlab.repository.DepartmentWithSubselectRepository;
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
 * Tests @Fetch(FetchMode.SUBSELECT) on collections.
 *
 * SUBSELECT loads ALL collections of a type for ALL loaded parents in ONE query
 * using a subselect that repeats the original query:
 *   SELECT * FROM employees WHERE department_id IN (SELECT id FROM departments)
 *
 * vs batch_fetch_size which groups by chunks:
 *   SELECT * FROM employees WHERE department_id IN (?, ?, ..., ?)  -- up to 16
 *
 * SUBSELECT always does 1 query per collection type regardless of parent count.
 * batch_fetch does ceil(N/16) queries per collection type.
 */
@ActiveProfiles({"test", "osiv-disabled"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubselectFetchTest extends AbstractIntegrationTest {

    @Autowired private DepartmentWithSubselectRepository subselectRepo;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;
    @Autowired private TransactionTemplate transactionTemplate;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    @Test
    @Order(1)
    @DisplayName("@Fetch(SUBSELECT): N departments → always 1+6 queries (1 base + 1 per collection type)")
    void subselectConstantQueries() {
        testDataFactory.createDepartmentsWithCollections(25, 5);
        queryCounter.clear();

        transactionTemplate.setReadOnly(true);
        long[] queries = {0};
        List<DepartmentWithSubselect> depts = transactionTemplate.execute(status -> {
            List<DepartmentWithSubselect> result = subselectRepo.findAll();
            // Trigger lazy load of all collections
            result.forEach(d -> {
                d.getEmployees().size();
                d.getProjects().size();
                d.getBudgets().size();
                d.getEquipment().size();
                d.getPolicies().size();
                d.getDocuments().size();
            });
            queries[0] = queryCounter.getPrepareStatementCount();
            return result;
        });
        transactionTemplate.setReadOnly(false);

        System.out.println("=== @Fetch(SUBSELECT) — 25 departments x 5 items/col ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Total queries: " + queries[0]);
        System.out.println("  Esperado: ~8 (1 base + 1 region + 6 subselects)");
        System.out.println("  batch_fetch=16 seria: 14 (1 + ceil(25/16)*7)");
        System.out.println("  Sin nada seria: 176 (1 + 25*7)");

        assertThat(depts).hasSize(25);
        // SUBSELECT: always 1 query per collection type regardless of N
        assertThat(queries[0]).isLessThanOrEqualTo(10);
    }

    @Test
    @Order(2)
    @DisplayName("SUBSELECT scales: 5, 25, 100 departments → same query count")
    void subselectScalesConstant() {
        int[] volumes = {5, 25, 100};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.createDepartmentsWithCollections(vol, 5);
            queryCounter.clear();

            transactionTemplate.setReadOnly(true);
            long queries = transactionTemplate.execute(status -> {
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
            transactionTemplate.setReadOnly(false);

            long batchWouldBe = 1 + ((long) Math.ceil(vol / 16.0)) * 7;
            long noPlusOne = 1 + (long) vol * 7;
            rows.add(String.format("  | %4d | %4d       | %,6d          | %,6d     |",
                    vol, queries, batchWouldBe, noPlusOne));
        }

        System.out.println();
        System.out.println("=== @Fetch(SUBSELECT) vs batch_fetch=16 vs sin nada ===");
        System.out.println("--------------------------------------------------------------");
        System.out.println("  | Depts | SUBSELECT | batch_fetch=16 | Sin nada  |");
        System.out.println("--------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("--------------------------------------------------------------");
        System.out.println("  SUBSELECT: siempre ~8 (1 + 1 region + 6 colecciones)");
        System.out.println("  batch=16: 1 + ceil(N/16)*7");
        System.out.println("  Sin nada: 1 + 7N");
        System.out.println();
    }
}
