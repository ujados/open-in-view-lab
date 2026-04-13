package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.service.StoreInitializeService;
import com.example.osivlab.service.StoreService;
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
 * Compares Hibernate.initialize() explicit loading vs @EntityGraph.
 * initialize() triggers N+1 (same as @Transactional without EntityGraph)
 * but gives explicit control over what gets loaded and when.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class HibernateInitializeTest extends AbstractIntegrationTest {

    @Autowired private StoreInitializeService initService;
    @Autowired private StoreService storeService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
        testDataFactory.createStores(10);
    }

    @Test
    @DisplayName("Hibernate.initialize() → same N+1 as @Transactional but explicit control")
    void initializeExplicitLoading() {
        queryCounter.clear();

        List<StoreDto> stores = initService.getAllStoresWithInitialize();

        long initQueries = queryCounter.getPrepareStatementCount();

        queryCounter.clear();
        List<StoreDto> egStores = storeService.getAllStoresWithEntityGraph();
        long egQueries = queryCounter.getPrepareStatementCount();

        System.out.println("=== Hibernate.initialize() vs @EntityGraph (10 Stores) ===");
        System.out.println("  Hibernate.initialize(): " + initQueries + " queries");
        System.out.println("  @EntityGraph:           " + egQueries + " queries");
        System.out.println("  initialize() es N+1 pero con control explicito de que se carga");

        assertThat(stores).hasSize(10);
        assertThat(initQueries).isGreaterThan(egQueries);
    }
}
