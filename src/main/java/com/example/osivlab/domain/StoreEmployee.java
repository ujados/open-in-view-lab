package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "store_employees")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class StoreEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;
}
