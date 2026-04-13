package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreProjection;
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
 * Tests DTO Projection solution — JPQL constructor expression.
 * 0 risk of LazyInitializationException, optimal SQL.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class DtoProjectionTest extends AbstractIntegrationTest {

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
    @DisplayName("DTO Projection → 1 query, no entity loaded, no lazy risk")
    void dtoProjectionSingleQuery() {
        queryCounter.clear();

        List<StoreProjection> stores = storeService.getAllStoresProjection();

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== DTO Projection (10 Stores) ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Stores returned: " + stores.size());

        assertThat(stores).hasSize(10);
        assertThat(queries).isEqualTo(1);
        assertThat(stores.get(0).storeTypeName()).isNotNull();
        assertThat(stores.get(0).regionName()).isNotNull();
    }
}
