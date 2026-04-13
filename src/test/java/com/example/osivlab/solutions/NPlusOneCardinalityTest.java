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
 * Demonstrates that N+1 severity depends on the CARDINALITY of referenced entities,
 * not the number of parent entities.
 *
 * - 10 stores sharing 2 storeTypes/regions/timezones → ~7 queries (1 + 2 + 2 + 2)
 * - 10 stores each with UNIQUE storeType/region/timezone → 31 queries (1 + 10 + 10 + 10)
 *
 * This is critical for understanding when N+1 actually hurts: it's the number of
 * DISTINCT referenced entities that drives the query count, not the parent count.
 */
@ActiveProfiles({"test", "osiv-disabled"})
class NPlusOneCardinalityTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    @Test
    @DisplayName("10 stores, 2 shared references → ~7 queries (N+1 on distinct refs, not parents)")
    void sharedReferencesReduceNPlusOne() {
        testDataFactory.createStores(10); // 2 storeTypes, 2 regions, 2 timezones shared
        queryCounter.clear();

        List<StoreDto> stores = storeService.getAllStoresTransactional();

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== N+1 with SHARED references (10 stores, 2 of each type) ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Stores returned: " + stores.size());
        System.out.println("  Total prepared statements: " + queries);
        System.out.println("  Expected: ~7 (1 base + 2 types + 2 regions + 2 tz)");

        assertThat(stores).hasSize(10);
        assertThat(queries).isLessThanOrEqualTo(7);
    }

    @Test
    @DisplayName("10 stores, 10 UNIQUE references each → 31 queries (true N+1)")
    void uniqueReferencesMaximizeNPlusOne() {
        testDataFactory.createStoresWithUniqueRelations(10); // each store has its own refs
        queryCounter.clear();

        List<StoreDto> stores = storeService.getAllStoresTransactional();

        long queries = queryCounter.getPrepareStatementCount();
        System.out.println("=== N+1 with UNIQUE references (10 stores, each with unique refs) ===");
        System.out.println("  " + queryCounter.getSummary());
        System.out.println("  Stores returned: " + stores.size());
        System.out.println("  Total prepared statements: " + queries);
        System.out.println("  Expected: 31 (1 base + 10 types + 10 regions + 10 tz)");

        assertThat(stores).hasSize(10);
        assertThat(queries).isGreaterThanOrEqualTo(20); // at least 20+ queries
    }
}
