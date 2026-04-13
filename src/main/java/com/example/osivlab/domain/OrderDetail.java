package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Complex entity inheriting AbstractOrderItem with 3 deep lazy relations.
 * Mapped from: RetailProduct (original codebase).
 */
@Entity
@Table(name = "order_details")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@SuperBuilder
public class OrderDetail extends AbstractOrderItem {

    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;
}
