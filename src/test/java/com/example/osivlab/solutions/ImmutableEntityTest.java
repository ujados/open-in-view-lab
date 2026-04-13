package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.DepartmentWithSets;
import com.example.osivlab.domain.Store;
import com.example.osivlab.repository.DepartmentWithSetsRepository;
import com.example.osivlab.repository.StoreRepository;
import jakarta.persistence.EntityManagerFactory;
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
 * Demonstrates @Immutable entity behavior.
 *
 * @Immutable tells Hibernate to skip dirty checking entirely for the entity.
 * No snapshots created, no flush needed. Modifications are silently ignored.
 *
 * Compares: regular entity (dirty checking active) vs @Immutable (no dirty checking).
 */
@ActiveProfiles({"test", "osiv-disabled"})
class ImmutableEntityTest extends AbstractIntegrationTest {

    @Autowired private StoreRepository storeRepository;
    @Autowired private DepartmentWithSetsRepository immutableRepo;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        testDataFactory.createDepartmentWithCollections(5);
        testDataFactory.createStores(5);
    }

    @Test
    @DisplayName("@Immutable entity: modifications silently ignored, no dirty checking overhead")
    void immutableIgnoresModifications() {
        // Get the actual ID of the created department
        Long deptId = transactionTemplate.execute(status ->
                immutableRepo.findAll().getFirst().getId());

        // Modify an @Immutable entity — should be silently ignored
        transactionTemplate.executeWithoutResult(status -> {
            DepartmentWithSets dept = immutableRepo.findById(deptId).orElseThrow();
            dept.setName("MODIFIED");
            // No save() needed — but even if we did, @Immutable prevents updates
        });

        // Verify the name was NOT changed
        DepartmentWithSets reloaded = transactionTemplate.execute(status ->
                immutableRepo.findById(deptId).orElseThrow());

        System.out.println("=== @Immutable ENTITY ===");
        System.out.println("  Name after 'modification': " + reloaded.getName());
        System.out.println("  @Immutable ignores modifications silently");
        System.out.println("  No dirty checking snapshots created → less memory");

        assertThat(reloaded.getName())
                .as("@Immutable should ignore modifications")
                .doesNotStartWith("MODIFIED");
    }

    @Test
    @DisplayName("Regular entity: modifications detected by dirty checking")
    void regularEntityDetectsModifications() {
        Long storeId = transactionTemplate.execute(status -> {
            Store store = storeRepository.findAll().getFirst();
            store.setName("MODIFIED-" + store.getName());
            // No save() — dirty checking persists at commit
            return store.getId();
        });

        Store reloaded = transactionTemplate.execute(status ->
                storeRepository.findById(storeId).orElseThrow());

        System.out.println("=== REGULAR ENTITY (no @Immutable) ===");
        System.out.println("  Name after modification: " + reloaded.getName());
        System.out.println("  Dirty checking detected and persisted the change");

        assertThat(reloaded.getName())
                .as("Regular entity should persist modifications via dirty checking")
                .startsWith("MODIFIED-");
    }
}
