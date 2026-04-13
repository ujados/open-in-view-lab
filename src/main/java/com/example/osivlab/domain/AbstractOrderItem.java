package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * Base class for order items (inheritance demo).
 * Mapped from: AbstractProduct (original codebase).
 */
@Entity
@Inheritance(strategy = InheritanceType.JOINED)
@Table(name = "order_items")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@SuperBuilder
public abstract class AbstractOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String itemName;

    private BigDecimal unitPrice;

    private Integer quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;
}
