package com.example.osivlab.service;

import com.example.osivlab.domain.Order;
import com.example.osivlab.domain.OrderStatus;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.springframework.stereotype.Service;

/**
 * Bulk insert using Hibernate StatelessSession.
 * No persistence context, no dirty checking, no L1 cache.
 * Ideal for high-volume writes.
 */
@Service
@RequiredArgsConstructor
public class BulkStatelessService {

    private final EntityManagerFactory emf;

    /**
     * Inserts N orders using StatelessSession — no persistence context overhead.
     */
    public void bulkInsertOrdersStateless(int count) {
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        try (StatelessSession session = sf.openStatelessSession()) {
            session.beginTransaction();
            for (int i = 0; i < count; i++) {
                Order order = Order.builder()
                        .code("STATELESS-" + i)
                        .description("Stateless order " + i)
                        .status(OrderStatus.PENDING)
                        .build();
                session.insert(order);
            }
            session.getTransaction().commit();
        }
    }

    /**
     * Inserts N orders using regular Session (with persistence context) for comparison.
     */
    public void bulkInsertOrdersSession(int count) {
        SessionFactory sf = emf.unwrap(SessionFactory.class);
        try (Session session = sf.openSession()) {
            session.beginTransaction();
            for (int i = 0; i < count; i++) {
                Order order = Order.builder()
                        .code("SESSION-" + i)
                        .description("Session order " + i)
                        .status(OrderStatus.PENDING)
                        .build();
                session.persist(order);
                if (i > 0 && i % 50 == 0) {
                    session.flush();
                    session.clear();
                }
            }
            session.getTransaction().commit();
        }
    }
}
