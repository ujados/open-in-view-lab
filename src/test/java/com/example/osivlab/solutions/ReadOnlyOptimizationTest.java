package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Store;
import com.example.osivlab.repository.StoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the impact of readOnly=true on Hibernate's dirty checking.
 *
 * With readOnly=true, Hibernate sets FlushMode.MANUAL and can skip creating
 * hydrated state snapshots (depending on the Hibernate version). This reduces
 * memory usage per loaded entity.
 *
 * This test verifies that readOnly transactions do NOT flush dirty entities,
 * proving that Hibernate disables dirty checking.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class ReadOnlyOptimizationTest extends AbstractIntegrationTest {

    @Autowired private StoreRepository storeRepository;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        testDataFactory.createStores(5);
    }

    @Test
    @DisplayName("readOnly=true → modifications are NOT flushed (dirty checking disabled)")
    void readOnlySkipsDirtyChecking() {
        // Modify entities in a readOnly transaction — changes should NOT be persisted
        transactionTemplate.setReadOnly(true);
        transactionTemplate.executeWithoutResult(status -> {
            List<Store> stores = storeRepository.findAll();
            stores.forEach(s -> s.setName("MODIFIED-" + s.getName()));
            // No explicit save — rely on dirty checking
            // With readOnly=true, Hibernate sets FlushMode.MANUAL → no auto-flush
        });
        transactionTemplate.setReadOnly(false); // reset for next operations

        // Verify names were NOT persisted
        List<Store> reloaded = transactionTemplate.execute(status -> storeRepository.findAll());

        System.out.println("=== readOnly=true OPTIMIZATION ===");
        reloaded.forEach(s -> System.out.println("  Store: " + s.getName()));

        boolean anyModified = reloaded.stream().anyMatch(s -> s.getName().startsWith("MODIFIED-"));
        assertThat(anyModified)
                .as("readOnly=true should prevent dirty checking flush")
                .isFalse();
        System.out.println("  Result: modifications were NOT persisted (dirty checking disabled)");
    }

    @Test
    @DisplayName("readOnly=false → modifications ARE flushed (dirty checking active)")
    void readWriteFlushesChanges() {
        // Modify entities in a normal (readWrite) transaction
        transactionTemplate.executeWithoutResult(status -> {
            List<Store> stores = storeRepository.findAll();
            stores.forEach(s -> s.setName("MODIFIED-" + s.getName()));
            // No explicit save — dirty checking should detect and flush
        });

        // Verify names WERE persisted
        List<Store> reloaded = transactionTemplate.execute(status -> storeRepository.findAll());

        System.out.println("=== readOnly=false (normal transaction) ===");
        reloaded.forEach(s -> System.out.println("  Store: " + s.getName()));

        boolean allModified = reloaded.stream().allMatch(s -> s.getName().startsWith("MODIFIED-"));
        assertThat(allModified)
                .as("Normal transaction should flush dirty entities via dirty checking")
                .isTrue();
        System.out.println("  Result: modifications WERE persisted (dirty checking active)");
    }
}
