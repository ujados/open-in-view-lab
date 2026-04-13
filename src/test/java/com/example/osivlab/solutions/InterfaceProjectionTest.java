package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreView;
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
 * Spring Data Interface Projection — less boilerplate than constructor DTO.
 * Just declare an interface with getters, Spring Data generates the implementation.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class InterfaceProjectionTest extends AbstractIntegrationTest {

    @Autowired private StoreRepository storeRepository;
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
    @DisplayName("Interface Projection → 1 query, no entities loaded, less boilerplate than constructor DTO")
    void interfaceProjectionSingleQuery() {
        queryCounter.clear();

        List<StoreView> stores = storeRepository.findAllProjectedBy();

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== Interface Projection (10 Stores) ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Stores returned: " + stores.size());

        assertThat(stores).hasSize(10);
        assertThat(queries).isEqualTo(1);
        assertThat(stores.get(0).getName()).isNotNull();
        assertThat(stores.get(0).getRegionName()).isNotNull();
    }
}
