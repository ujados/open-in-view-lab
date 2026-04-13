package com.example.osivlab.write;

import com.example.osivlab.AbstractWebIntegrationTest;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Order;
import com.example.osivlab.domain.OrderStatus;
import com.example.osivlab.repository.OrderRepository;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Demonstrates how @Version (optimistic locking) interacts with OSIV.
 *
 * With OSIV=true: the entity stays managed for the entire request.
 * If two concurrent modifications happen on the same entity, the second
 * one gets an OptimisticLockException at flush time (request end),
 * which is LATE and hard to handle gracefully.
 *
 * With OSIV=false: the entity is detached after the transaction.
 * OptimisticLockException happens at merge() time inside @Transactional,
 * which is EARLY and easy to handle.
 */
class OptimisticLockingTest {

    @Nested
    @ActiveProfiles({"test", "osiv-disabled"})
    @DisplayName("Optimistic Locking sin OSIV")
    class WithoutOsiv extends AbstractWebIntegrationTest {

        @Autowired private OrderRepository orderRepository;
        @Autowired private TestDataFactory testDataFactory;
        @Autowired private TransactionTemplate transactionTemplate;

        private Order order;

        @BeforeEach
        void setUp() {
            testDataFactory.cleanAll();
            order = testDataFactory.createOrder();
        }

        @Test
        @DisplayName("@Version detects concurrent modification → OptimisticLockException")
        void optimisticLockDetectsConcurrentModification() {
            // Simulate two users loading the same order
            Order user1Copy = transactionTemplate.execute(s ->
                    orderRepository.findById(order.getId()).orElseThrow());
            Order user2Copy = transactionTemplate.execute(s ->
                    orderRepository.findById(order.getId()).orElseThrow());

            // User 1 saves first — succeeds
            transactionTemplate.executeWithoutResult(s -> {
                Order managed = orderRepository.findById(user1Copy.getId()).orElseThrow();
                managed.setStatus(OrderStatus.PROCESSING);
                orderRepository.saveAndFlush(managed);
            });

            // User 2 tries to save with stale version — fails
            assertThatThrownBy(() ->
                    transactionTemplate.executeWithoutResult(s -> {
                        user2Copy.setStatus(OrderStatus.CANCELLED);
                        orderRepository.saveAndFlush(user2Copy);
                    })
            ).hasRootCauseInstanceOf(StaleObjectStateException.class);

            // Verify user1's change persisted
            Order reloaded = transactionTemplate.execute(s ->
                    orderRepository.findById(order.getId()).orElseThrow());
            assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PROCESSING);
            assertThat(reloaded.getVersion()).isEqualTo(1L);

            System.out.println("=== OPTIMISTIC LOCKING (OSIV=false) ===");
            System.out.println("  User1 save: OK (version 0 → 1)");
            System.out.println("  User2 save: OptimisticLockException (stale version 0)");
            System.out.println("  Deteccion: TEMPRANA (dentro de @Transactional)");
        }
    }
}
