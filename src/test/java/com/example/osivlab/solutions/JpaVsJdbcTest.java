package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.dto.StoreProjection;
import com.example.osivlab.service.StoreJdbcService;
import com.example.osivlab.service.StoreService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Head-to-head comparison: JPA/Hibernate vs JdbcClient for the same query.
 *
 * Both return the same data (StoreProjection), but:
 * - JPA DTO Projection: goes through Hibernate query parser, EntityManager, statistics tracking
 * - JdbcClient: raw SQL → RowMapper → DTO, zero Hibernate overhead
 *
 * Tests at 1K, 10K, 100K, 500K records to show where the overhead matters.
 */
@ActiveProfiles({"test", "large-scale"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JpaVsJdbcTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private StoreJdbcService storeJdbcService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    @Test
    @Order(1)
    @DisplayName("JPA DTO Projection vs JdbcClient: same query, different overhead (1K → 500K)")
    void jpaVsJdbcProjection() {
        int[] volumes = {1_000, 10_000, 100_000, 500_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertStoresWithSharedRefs(vol);

            // JPA DTO Projection
            queryCounter.clear();
            long jpaStart = System.currentTimeMillis();
            List<StoreProjection> jpaResult = storeService.getAllStoresProjection();
            long jpaTime = System.currentTimeMillis() - jpaStart;
            long jpaQueries = queryCounter.getPrepareStatementCount();

            // JdbcClient (no Hibernate)
            long jdbcStart = System.currentTimeMillis();
            List<StoreProjection> jdbcResult = storeJdbcService.getAllStoresProjection();
            long jdbcTime = System.currentTimeMillis() - jdbcStart;

            assertThat(jpaResult).hasSize(vol);
            assertThat(jdbcResult).hasSize(vol);

            String faster = jdbcTime < jpaTime ? "JDBC" : "JPA";
            long diff = Math.abs(jpaTime - jdbcTime);
            rows.add(String.format("  | %,8d | %,7d ms (%d q) | %,7d ms       | %s gana por %,d ms",
                    vol, jpaTime, jpaQueries, jdbcTime, faster, diff));
        }

        System.out.println();
        System.out.println("=== JPA DTO Projection vs JdbcClient (misma query, mismo resultado) ===");
        System.out.println("------------------------------------------------------------------------");
        System.out.println("  | Records  | JPA Projection    | JdbcClient      | Diferencia");
        System.out.println("------------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("------------------------------------------------------------------------");
        System.out.println("  JPA: pasa por Hibernate query parser + EntityManager + statistics");
        System.out.println("  JDBC: SQL directo -> RowMapper -> DTO, zero Hibernate overhead");
        System.out.println();
    }

    @Test
    @Order(2)
    @DisplayName("JPA @EntityGraph (full entity) vs JdbcClient (minimal DTO) — overhead real")
    void entityGraphVsJdbc() {
        int[] volumes = {1_000, 10_000, 100_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.bulkInsertStoresWithSharedRefs(vol);

            // JPA @EntityGraph — loads full entities + persistence context
            queryCounter.clear();
            long egStart = System.currentTimeMillis();
            List<StoreDto> egResult = storeService.getAllStoresWithEntityGraph();
            long egTime = System.currentTimeMillis() - egStart;

            // JdbcClient — loads only needed columns, no entity management
            long jdbcStart = System.currentTimeMillis();
            List<StoreProjection> jdbcResult = storeJdbcService.getAllStoresProjection();
            long jdbcTime = System.currentTimeMillis() - jdbcStart;

            assertThat(egResult).hasSize(vol);
            assertThat(jdbcResult).hasSize(vol);

            double ratio = (double) egTime / Math.max(jdbcTime, 1);
            rows.add(String.format("  | %,8d | %,7d ms         | %,7d ms       | @EntityGraph %.1fx mas lento",
                    vol, egTime, jdbcTime, ratio));
        }

        System.out.println();
        System.out.println("=== @EntityGraph (full entity) vs JdbcClient (minimal DTO) ===");
        System.out.println("------------------------------------------------------------------------");
        System.out.println("  | Records  | @EntityGraph      | JdbcClient      | Ratio");
        System.out.println("------------------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("------------------------------------------------------------------------");
        System.out.println("  @EntityGraph: 1 query pero carga entidades + snapshots + persistence context");
        System.out.println("  JdbcClient: 1 query, solo los campos necesarios, zero overhead");
        System.out.println();
    }
}
