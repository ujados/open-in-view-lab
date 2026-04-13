package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.DepartmentWithBatchSize;
import com.example.osivlab.repository.DepartmentWithBatchSizeRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests @BatchSize(16) per-collection annotation.
 *
 * Same effect as global default_batch_fetch_size=16 but on individual collections.
 * Allows different batch sizes per collection when needed.
 *
 * Uses osiv-disabled profile (no global batch_fetch_size) to isolate the annotation effect.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class BatchSizePerCollectionTest extends AbstractIntegrationTest {

    @Autowired private DepartmentWithBatchSizeRepository batchRepo;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;
    @Autowired private TransactionTemplate transactionTemplate;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
        testDataFactory.createDepartmentsWithCollections(25, 5);
    }

    @Test
    @DisplayName("@BatchSize(16) per collection: same as global batch_fetch_size but per-collection")
    void batchSizePerCollection() {
        queryCounter.clear();

        transactionTemplate.setReadOnly(true);
        long[] queries = {0};
        List<DepartmentWithBatchSize> depts = transactionTemplate.execute(status -> {
            List<DepartmentWithBatchSize> result = batchRepo.findAll();
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

        long withoutBatch = 1 + 25L * 7; // 176

        System.out.println("=== @BatchSize(16) per collection — 25 departments x 5 items/col ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Total queries: " + queries[0]);
        System.out.println("  Sin @BatchSize seria: " + withoutBatch);
        System.out.println("  Mismo efecto que default_batch_fetch_size=16 pero por coleccion");

        assertThat(depts).hasSize(25);
        // Same as global batch_fetch: ~14 queries
        assertThat(queries[0]).isLessThan(20);
    }
}
