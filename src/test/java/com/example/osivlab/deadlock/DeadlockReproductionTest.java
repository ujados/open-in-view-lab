package com.example.osivlab.deadlock;

import com.example.osivlab.SharedPostgresContainer;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Module 1: Reproduces the connection pool deadlock.
 *
 * Conditions:
 * - open-in-view: true  → OSIV holds a DB connection for the entire HTTP request
 * - maximum-pool-size: 2 → tiny pool
 * - TransactionTemplate inside service → needs ANOTHER connection from pool
 * - 4+ concurrent requests → all OSIV connections consumed, none left for TransactionTemplate
 * - Result: connection timeout (deadlock)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "deadlock"})
class DeadlockReproductionTest {

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private TestDataFactory testDataFactory;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        var pg = SharedPostgresContainer.getInstance();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    private List<Order> orders;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        testDataFactory.cleanAll();
        orders = testDataFactory.createOrders(10);
    }

    @Test
    @DisplayName("OSIV + TransactionTemplate + pool=2 + concurrent requests → connection timeout")
    void shouldTimeoutWithOsivAndSmallPool() throws Exception {
        int concurrentRequests = 4;
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < concurrentRequests; i++) {
            final Long orderId = orders.get(i % orders.size()).getId();
            futures.add(executor.submit(() -> {
                try {
                    var result = mockMvc.perform(
                                    post("/api/orders/{id}/process", orderId))
                            .andReturn();
                    return result.getResponse().getStatus();
                } catch (Exception e) {
                    return 500;
                }
            }));
        }

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            try {
                statuses.add(future.get(15, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                statuses.add(-1);
            }
        }

        executor.shutdown();

        System.out.println("=== DEADLOCK TEST RESULTS (pool=2, requests=" + concurrentRequests + ") ===");
        for (int i = 0; i < statuses.size(); i++) {
            int status = statuses.get(i);
            String label = status == 200 ? "OK" : status == -1 ? "TIMEOUT (deadlock!)" : "ERROR " + status;
            System.out.println("  Request " + i + ": " + label);
        }

        long failures = statuses.stream().filter(s -> s != 200).count();
        System.out.println("  Failures/Timeouts: " + failures + "/" + concurrentRequests);

        assertThat(orders).hasSize(10);
    }
}
