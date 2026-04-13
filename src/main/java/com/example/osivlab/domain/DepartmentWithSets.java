package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

import java.util.HashSet;
import java.util.Set;

/**
 * Read-only view of Department using Set collections instead of List.
 * Uses @Subselect so Hibernate does NOT create/modify a table for this entity.
 *
 * Demonstrates that changing List→Set avoids MultipleBagFetchException
 * but silently produces a cartesian product with @EntityGraph.
 */
@Entity
@Subselect("SELECT id, name, code, region_id FROM departments")
@Immutable
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DepartmentWithSets {

    @Id
    private Long id;

    private String name;

    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false)
    private Region region;

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Builder.Default
    private Set<Employee> employees = new HashSet<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Builder.Default
    private Set<Project> projects = new HashSet<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Builder.Default
    private Set<Budget> budgets = new HashSet<>();
}
