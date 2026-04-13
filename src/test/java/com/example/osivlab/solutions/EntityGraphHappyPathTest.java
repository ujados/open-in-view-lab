package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Store;
import com.example.osivlab.repository.StoreRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates that @EntityGraph works perfectly with ManyToOne + 1 single collection.
 * The previous tests showed failures (MultipleBagFetchException, cartesian product).
 * This test shows the HAPPY PATH: when @EntityGraph is the right choice.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class EntityGraphHappyPathTest extends AbstractIntegrationTest {

    @Autowired private StoreRepository storeRepository;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
        testDataFactory.createStores(5);
    }

    @Test
    @DisplayName("@EntityGraph with 3 ManyToOne + 1 collection → works fine (1 query)")
    void entityGraphWithSingleCollectionWorks() {
        queryCounter.clear();

        List<Store> stores = storeRepository.findAllWithRelationsAndEmployeesBy();

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== @EntityGraph HAPPY PATH (3 ManyToOne + 1 collection) ===");
        System.out.println("  Queries: " + queries);
        System.out.println("  Stores: " + stores.size());
        System.out.println("  Funciona porque es 1 sola coleccion (no cartesiano)");

        assertThat(stores).hasSize(5);
        assertThat(queries).isEqualTo(1);
    }
}
