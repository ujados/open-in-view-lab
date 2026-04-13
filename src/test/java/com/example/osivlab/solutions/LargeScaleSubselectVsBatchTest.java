package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.DepartmentWithBatchSize;
import com.example.osivlab.domain.DepartmentWithSubselect;
import com.example.osivlab.dto.DepartmentDto;
import com.example.osivlab.repository.DepartmentWithBatchSizeRepository;
import com.example.osivlab.repository.DepartmentWithSubselectRepository;
import com.example.osivlab.service.DepartmentService;
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
 * SUBSELECT vs @BatchSize vs @Transactional (no batch) at large scale.
 * Department with 6 collections: 25 → 5K departments.
 *
 * Shows that SUBSELECT stays constant while @BatchSize grows with ceil(N/16)
 * and @Transactional without batch grows linearly.
 */
@ActiveProfiles({"test", "osiv-disabled"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LargeScaleSubselectVsBatchTest extends AbstractIntegrationTest {

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
    @DisplayName("SUBSELECT vs @BatchSize(16) vs sin nada: 25 → 5K departments")
    void subselectVsBatchVsNone() {
        int[] volumes = {25, 100, 500, 1_000, 5_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertDepartmentsWithCollections(vol, 5);

            // @Transactional sin batch (N+1 puro)
            queryCounter.clear();
            long t1 = System.currentTimeMillis();
            List<DepartmentDto> txResult = departmentService.getAllDepartmentsTransactional();
            long txTime = System.currentTimeMillis() - t1;
            long txQ = queryCounter.getPrepareStatementCount();
            assertThat(txResult).hasSize(vol);

            // @BatchSize(16) per collection
            queryCounter.clear();
            transactionTemplate.setReadOnly(true);
            long t2 = System.currentTimeMillis();
            long batchQ = transactionTemplate.execute(status -> {
                List<DepartmentWithBatchSize> depts = batchSizeRepo.findAll();
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
            long batchTime = System.currentTimeMillis() - t2;
            transactionTemplate.setReadOnly(false);

            // @Fetch(SUBSELECT)
            queryCounter.clear();
            transactionTemplate.setReadOnly(true);
            long t3 = System.currentTimeMillis();
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
            long subTime = System.currentTimeMillis() - t3;
            transactionTemplate.setReadOnly(false);

            rows.add(String.format("  | %,5d | %,6dq %,7dms | %,5dq %,7dms | %,4dq %,7dms |",
                    vol, txQ, txTime, batchQ, batchTime, subQ, subTime));
        }

        System.out.println();
        System.out.println("=== SUBSELECT vs @BatchSize(16) vs sin nada (N depts x 5 items/col) ===");
        System.out.println("--------------------------------------------------------------------------");
        System.out.printf("  | %-5s | %-18s | %-18s | %-18s |%n",
                "Depts", "Sin nada (N+1)", "@BatchSize(16)", "SUBSELECT");
        System.out.println("--------------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("--------------------------------------------------------------------------");
        System.out.println("  Sin nada: 1 + 7N | @BatchSize: 1 + ceil(N/16)*7 | SUBSELECT: ~8 constante");
        System.out.println();
    }
}
