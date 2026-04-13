package com.example.osivlab.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of Department with @Fetch(FetchMode.SUBSELECT) on collections.
 *
 * SUBSELECT loads ALL collections of a type in a single query using a subquery
 * that repeats the original query. Different from batch_fetch_size which groups
 * by chunks of N.
 *
 * Example: if you loaded departments 1,2,3, accessing employees of dept 1 triggers:
 * SELECT * FROM employees WHERE department_id IN (SELECT id FROM departments WHERE ...)
 * This loads employees for ALL 3 departments in one shot.
 */
@Entity
@Subselect("SELECT id, name, code, region_id FROM departments")
@Immutable
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class DepartmentWithSubselect {

    @Id
    private Long id;

    private String name;
    private String code;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", insertable = false, updatable = false)
    private Region region;

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<Employee> employees = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<Project> projects = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<Budget> budgets = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<Equipment> equipment = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<Policy> policies = new ArrayList<>();

    @OneToMany
    @JoinColumn(name = "department_id", insertable = false, updatable = false)
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private List<Document> documents = new ArrayList<>();
}
