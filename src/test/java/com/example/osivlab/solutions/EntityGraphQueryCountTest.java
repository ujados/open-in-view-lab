package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreDto;
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
 * Tests @EntityGraph solution — measures query count for Store (3 ManyToOne).
 */
@ActiveProfiles({"test", "osiv-disabled"})
class EntityGraphQueryCountTest extends AbstractIntegrationTest {

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
    @DisplayName("@EntityGraph → 1 JOIN query for 10 stores with 3 ManyToOne relations")
    void entityGraphSingleQuery() {
        queryCounter.clear();

        List<StoreDto> stores = storeService.getAllStoresWithEntityGraph();

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== @EntityGraph (10 Stores, 3 ManyToOne) ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Stores returned: " + stores.size());

        assertThat(stores).hasSize(10);
        assertThat(queries).isEqualTo(1); // Single JOIN query
    }
}
