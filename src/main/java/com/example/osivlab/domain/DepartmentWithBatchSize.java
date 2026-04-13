package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of Department with @BatchSize(16) on each collection.
 *
 * Same effect as global default_batch_fetch_size=16 but per-collection.
 * Allows different batch sizes per collection if needed.
 */
@Entity
@Subselect("SELECT id, name, code, region_id FROM departments")
@Immutable
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DepartmentWithBatchSize {

    @Id
    private Long id;

    private String name;
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false)
    private Region region;

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @BatchSize(size = 16)
    @Builder.Default
    private List<Employee> employees = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @BatchSize(size = 16)
    @Builder.Default
    private List<Project> projects = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @BatchSize(size = 16)
    @Builder.Default
    private List<Budget> budgets = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @BatchSize(size = 16)
    @Builder.Default
    private List<Equipment> equipment = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @BatchSize(size = 16)
    @Builder.Default
    private List<Policy> policies = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @BatchSize(size = 16)
    @Builder.Default
    private List<Document> documents = new ArrayList<>();
}
