package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Store;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.dto.StoreProjection;
import com.example.osivlab.repository.StoreRepository;
import com.example.osivlab.service.StoreService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Tuple;
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
 * Demonstrates OVER-FETCHING: loading more data than needed.
 *
 * When you only need store name + region name, the different solutions
 * transfer very different amounts of data:
 *
 * - @EntityGraph: loads FULL Store entity + ALL 3 relations (type, region, tz)
 *   even if you only need region name
 * - @Transactional: loads FULL Store + triggers lazy load of ALL accessed relations
 * - DTO Projection: loads ONLY the 2 columns you need
 *
 * This matters at scale: 100K stores x (all columns) vs 100K x (2 columns).
 */
@ActiveProfiles({"test", "large-scale"})
class OverFetchingTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private StoreRepository storeRepository;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transactionTemplate;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
        testDataFactory.bulkInsertStoresWithSharedRefs(10_000);
    }

    @Test
    @DisplayName("@EntityGraph: 1 query but loads ALL columns of Store + 3 relations (over-fetch)")
    void entityGraphOverFetches() {
        queryCounter.clear();
        long start = System.currentTimeMillis();

        List<StoreDto> stores = storeService.getAllStoresWithEntityGraph();
        long elapsed = System.currentTimeMillis() - start;
        long queries = queryCounter.getPrepareStatementCount();

        System.out.println("=== OVER-FETCHING: @EntityGraph (10K stores) ===");
        System.out.println("  Queries: " + queries);
        System.out.println("  Tiempo: " + elapsed + " ms");
        System.out.println("  Columnas transferidas: id, name, address, store_type_id, region_id, timezone_id");
        System.out.println("    + TODAS las columnas de store_types, regions, timezones (JOIN)");
        System.out.println("  Entidades en memoria: " + stores.size() + " Store + types + regions + tz");

        assertThat(stores).hasSize(10_000);
        assertThat(queries).isEqualTo(1);
    }

    @Test
    @DisplayName("DTO Projection: 1 query, loads ONLY the columns you need")
    void dtoProjectionMinimalFetch() {
        queryCounter.clear();
        long start = System.currentTimeMillis();

        List<StoreProjection> stores = storeService.getAllStoresProjection();
        long elapsed = System.currentTimeMillis() - start;
        long queries = queryCounter.getPrepareStatementCount();

        System.out.println("=== MINIMAL FETCH: DTO Projection (10K stores) ===");
        System.out.println("  Queries: " + queries);
        System.out.println("  Tiempo: " + elapsed + " ms");
        System.out.println("  Columnas transferidas: s.id, s.name, s.address, st.name, r.name, tz.zone_id");
        System.out.println("  Entidades en memoria: 0 (solo records planos)");

        assertThat(stores).hasSize(10_000);
        assertThat(queries).isEqualTo(1);
    }

    @Test
    @DisplayName("Projection con solo 2 columnas: minimo absoluto de datos transferidos")
    void minimalProjectionTwoColumns() {
        queryCounter.clear();
        long start = System.currentTimeMillis();

        transactionTemplate.setReadOnly(true);
        List<Tuple> results = transactionTemplate.execute(status ->
                entityManager.createQuery(
                        "SELECT s.name, r.name FROM Store s LEFT JOIN s.region r", Tuple.class)
                        .getResultList());
        transactionTemplate.setReadOnly(false);
        long elapsed = System.currentTimeMillis() - start;
        long queries = queryCounter.getPrepareStatementCount();

        System.out.println("=== MINIMAL ABSOLUTO: solo 2 columnas (10K stores) ===");
        System.out.println("  Queries: " + queries);
        System.out.println("  Tiempo: " + elapsed + " ms");
        System.out.println("  Columnas transferidas: s.name, r.name (solo 2!)");
        System.out.println("  Entidades en memoria: 0");

        assertThat(results).hasSize(10_000);
        assertThat(queries).isEqualTo(1);
    }
}
