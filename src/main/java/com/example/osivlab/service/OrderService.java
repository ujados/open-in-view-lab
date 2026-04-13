package com.example.osivlab.service;

import com.example.osivlab.domain.Order;
import com.example.osivlab.domain.OrderStatus;
import com.example.osivlab.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Service that reproduces the deadlock pattern:
 * 1. findById (uses OSIV connection or opens new one)
 * 2. TransactionTemplate { save } (acquires a 2nd connection from pool)
 * 3. post-hook (OSIV still holds the 1st connection)
 *
 * With a small pool + concurrent virtual threads → deadlock.
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * Simulates the 3-phase pattern: pre-hook → transaction → post-hook.
     * With OSIV=true, the outer session holds a connection for the entire request,
     * and TransactionTemplate needs another one from the pool.
     */
    public Order processOrder(Long orderId) {
        // Phase 1: pre-hook (read outside explicit transaction — OSIV keeps session open)
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // Phase 2: explicit transaction (needs a NEW connection from pool)
        transactionTemplate.executeWithoutResult(status -> {
            order.setStatus(OrderStatus.PROCESSING);
            orderRepository.save(order);
        });

        // Phase 3: post-hook (OSIV session still holds original connection)
        order.setStatus(OrderStatus.COMPLETED);
        return order;
    }
}
