package com.example.osivlab.write;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.service.BulkStatelessService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares StatelessSession vs regular Session for bulk inserts.
 * StatelessSession has no persistence context, no dirty checking, no L1 cache.
 */
@ActiveProfiles({"test", "large-scale"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatelessSessionTest extends AbstractIntegrationTest {

    @Autowired private BulkStatelessService bulkService;
    @Autowired private TestDataFactory testDataFactory;

    @Test
    @Order(1)
    @DisplayName("StatelessSession vs Session: bulk insert 100, 1K, 5K orders")
    void statelessVsSession() {
        int[] volumes = {100, 1_000, 5_000};
        List<String> rows = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            long statelessStart = System.currentTimeMillis();
            bulkService.bulkInsertOrdersStateless(vol);
            long statelessTime = System.currentTimeMillis() - statelessStart;

            testDataFactory.cleanAll();
            long sessionStart = System.currentTimeMillis();
            bulkService.bulkInsertOrdersSession(vol);
            long sessionTime = System.currentTimeMillis() - sessionStart;

            String faster = statelessTime < sessionTime ? "Stateless" : "Session";
            rows.add(String.format("  | %,6d | %,7d ms       | %,7d ms    | %s gana",
                    vol, statelessTime, sessionTime, faster));
        }

        System.out.println();
        System.out.println("=== ESCRITURA: StatelessSession vs Session (bulk insert) ===");
        System.out.println("----------------------------------------------------------------");
        System.out.println("  | Orders | StatelessSession | Session      | Resultado");
        System.out.println("----------------------------------------------------------------");
        rows.forEach(System.out::println);
        System.out.println("----------------------------------------------------------------");
        System.out.println("  StatelessSession: sin persistence context, sin dirty checking");
        System.out.println("  Session: con persistence context, flush+clear cada 50 entities");
        System.out.println();
    }
}
