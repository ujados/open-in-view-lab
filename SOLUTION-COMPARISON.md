# Solution comparison: disabling Open-in-View

When you disable `spring.jpa.open-in-view=false`, every lazy relationship accessed outside an explicit transaction blows up with `LazyInitializationException`. The question is no longer *whether* to disable it, but *how* to load the data you need without relying on the OSIV filter's open session.

There is no single correct solution. Each strategy has a different range of applicability, and the best choice depends on the shape of the entities you work with: how many relationships they have, whether they are `ManyToOne` or collections, and whether you need the entity for writes or just for reads.

Below I present five strategies, measured with Hibernate Statistics on a real PostgreSQL instance (Testcontainers), with the exact numbers the lab produced. All tests in the project pass green and back every number shown here.

---

## Summary table

| Solution | Queries (10 Store, 3 ManyToOne) | Queries (1 Dept, 6 collections x5) | Cartesian? | Memory (100K) | Effort |
|---|:---:|:---:|:---:|:---:|---|
| `@EntityGraph` | **1** | `MultipleBagFetchException` | Yes | 117 MB | Annotation on repository |
| `@Transactional(readOnly=true)` | 7 or 31 (*) | 8 | No | ~117 MB | Annotation on service |
| `batch_fetch_size=16` | **4** | 8 | No | ~117 MB | 1 line in `application.yml` |
| `@BatchSize(16)` per collection | n/a | 13 (25 depts) | No | ~117 MB | Annotation per collection |
| `@Fetch(SUBSELECT)` | n/a | **7** (25 depts) | No | ~117 MB | Annotation per collection |
| `Hibernate.initialize()` | 7 | 8 | No | ~117 MB | Explicit manual code |
| Split Queries | n/a | **7** | No | ~117 MB | Manual code |
| DTO Projection (JPQL) | **1** | **7** | No | 56 MB | JPQL query + record |
| Interface Projection | **1** | n/a | No | 130 MB | Interface + @Query |
| JdbcClient | **1** | **7** | No | 56 MB | Manual SQL |
| `@Immutable` | n/a | n/a (read-only) | No | <117 MB | Annotation on entity |
| StatelessSession | n/a (writes) | n/a | No | ~0 | Hibernate API directly |

> (*) 7 queries when the 10 stores share 2 references of each type. **31 queries** when each store has unique references. The N+1 depends on the cardinality of the referenced entities, not the number of parent entities.
>
> The "Memory (100K)" column comes from `MemoryFootprintTest` + `LargeScaleAllSolutionsTest`: @EntityGraph loads **118 MB** of entities + snapshots, DTO only **59 MB** of flat records. Interface Projection surprises at **130 MB** (dynamic proxies heavier than managed entities).

---

## 1. `@EntityGraph`

```java
@EntityGraph(attributePaths = {"storeType", "region", "timezone"})
List<Store> findAllWithAllRelationsBy();
```

Hibernate generates a single `SELECT` with a `LEFT JOIN` for each declared relationship. For `Store` with 3 `ManyToOne` relations, the result is **1 query** returning 10 rows.

> **Test:** `EntityGraphQueryCountTest` -- `prepared=1, entityFetch=0, queryExec=1`

### The problem with collections

If you try an `@EntityGraph` on `Department` with 6 `List<>` fields, Hibernate throws `MultipleBagFetchException` because it cannot fetch multiple bags (lists) in a single query. Even if you changed the lists to `Set`, you would get a silent cartesian product: with 5 items per collection, the query would return 5^6 = 15,625 rows for a single department.

> **Test:** `CartesianProductTest` -- captures the `MultipleBagFetchException` and demonstrates that `@EntityGraph` with multiple `List` does not work.

### The List-to-Set workaround: worse than the disease

The usual "solution" found on StackOverflow is to change `List` to `Set`. This avoids the exception but produces a **silent cartesian product**. With 3 `Set` collections of 5 items each, the database returns 5 * 5 * 5 = **125 rows** for 15 actual items. Hibernate deduplicates in memory, so the result looks correct -- but the data transfer is explosive.

With 50 items per collection: 50^3 = **125,000 rows** for 150 items. This is worse than `MultipleBagFetchException` because it is completely silent.

> **Test:** `CartesianProductWithSetsTest` -- demonstrates that with `Set` the query returns 1 result, Hibernate shows 5+5+5 correct items, but the database transferred 125 rows.

### The pagination trap

**This is critical and easy to overlook.** If you combine `@EntityGraph` with `Pageable` on a collection, Hibernate issues the warning `HHH90003004` and paginates **in memory**: it loads ALL rows from the database and trims in Java. The generated SQL has no `LIMIT`/`OFFSET`.

With 10 departments requesting a page of 3:
- `page.getContent().size()` returns 3 (looks correct)
- `page.getTotalElements()` returns 10
- But Hibernate loaded all 10 departments with all their employees into memory before returning 3

With 100K records this kills the application without warning.

> **Test:** `PaginationTrapTest` -- demonstrates that `findAllWithEmployeesBy(PageRequest.of(0, 3))` loads all 10 departments into memory, while `findAll(PageRequest.of(0, 3))` uses real `LIMIT`/`OFFSET`.

| Pros | Cons |
|---|---|
| Single SQL query | Cartesian product with multiple collections |
| Zero extra configuration | `MultipleBagFetchException` with >1 `List` |
| Declarative and readable | In-memory pagination with collections |
| | Does not scale to complex graphs |

**When to use:** entities with **1-2 `ManyToOne` relationships** or **a single collection without pagination**. The classic case: `Product` with its `Category`, `Store` with `StoreType`/`Region`/`Timezone`.

---

## 2. `@Transactional(readOnly = true)`

```java
@Transactional(readOnly = true)
public List<StoreDto> getAllStoresTransactional() {
    return storeRepository.findAll().stream()
            .map(storeMapper::toDto)
            .toList();
}
```

The annotation keeps the Hibernate session open for the entire method. Each lazy relationship is loaded on demand when the mapper accesses it.

### N+1 depends on cardinality, not on parent count

It is easy to assume that 10 stores with 3 `ManyToOne` relationships generate 31 queries (1 + 3*10). But the lab measured two distinct scenarios:

| Scenario | Queries | Why |
|---|:---:|---|
| 10 stores, 2 shared storeTypes/regions/tz | **7** | 1 base + 2 + 2 + 2 (distinct referenced entities) |
| 10 stores, each with **unique** refs | **31** | 1 base + 10 + 10 + 10 (all refs are distinct) |

Hibernate does not fire 1 query per proxy, but 1 per **distinct referenced entity**. If 10 stores point to the same 2 `StoreType` values, Hibernate only loads those 2 -- not 10. The N+1 is proportional to the cardinality of the referenced entities, not the number of parent entities.

In production, where catalogs (types, regions, time zones) are typically small shared tables, the real N+1 is much lower than the theoretical calculation suggests. But if each parent entity references something unique (like `createdBy` pointing to distinct users), the N+1 hits at full force.

> **Test:** `NPlusOneCardinalityTest` -- `sharedReferencesReduceNPlusOne` measures 7, `uniqueReferencesMaximizeNPlusOne` measures 31. Same 10 stores, different cardinality.

### readOnly=true disables dirty checking

`readOnly=true` is not just a semantic optimization. Hibernate sets `FlushMode.MANUAL`, which means:
- Modifications to entities within the transaction **are not persisted**
- Hibernate can skip state snapshots (less memory per entity)
- There is no automatic flush on commit

The lab demonstrates this directly: in a `readOnly=true` transaction, modifying the name of 5 stores without calling `save()` persists nothing. In a normal transaction, dirty checking detects the changes and persists them automatically on commit.

> **Test:** `ReadOnlyOptimizationTest` -- `readOnlySkipsDirtyChecking` modifies entities without save -> not persisted. `readWriteFlushesChanges` does the same without readOnly -> changes persisted.

| Pros | Cons |
|---|---|
| Works with any depth | N+1 queries (severity depends on cardinality) |
| No cartesian product | Holds a pool connection for the entire method |
| readOnly=true reduces memory | If the method is slow, the connection is wasted |

**When to use:** as a **universal fallback** for deep graphs or entities with many relationships. **Always combined with `batch_fetch_size`** to reduce the N+1. And always with `readOnly=true` on read methods.

### Beware of connection retention

`@Transactional` holds a HikariCP pool connection for the entire method execution. If the method contains slow logic (heavy mapping, HTTP calls, processing), that connection is blocked without executing SQL.

With virtual threads and a small pool, this reproduces exactly the same pattern that caused the deadlock with OSIV: too many threads holding connections for too long. The lab's deadlock test (`DeadlockReproductionTest`) demonstrates this pattern with `pool=2` and 4 concurrent virtual threads.

> **Test:** `DeadlockReproductionTest` -- 4 concurrent requests with pool=2 and OSIV+TransactionTemplate.

---

## 3. `default_batch_fetch_size`

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 16
```

A single line of configuration that changes how Hibernate loads lazy relationships. Instead of issuing 1 query per uninitialized proxy, Hibernate groups up to 16 proxies of the same type into a single query with `WHERE id IN (?, ?, ..., ?)`.

| Scenario | Without batch | With batch=16 | Reduction |
|---|:---:|:---:|:---:|
| 10 stores, 3 ManyToOne (shared refs) | 7 | **4** | 43% |
| 10 stores, 3 ManyToOne (unique refs) | 31 | **4** | 87% |
| 1 dept, 6 collections x5 items | 8 | **8** | 0% |

Batch fetch shines with `ManyToOne` relationships where there are many distinct references: it converts 31 queries into 4 (1 base + 1 batch per type). For `OneToMany` collections, the benefit is smaller because each collection is already loaded in 1 query per parent entity.

> **Test:** `BatchSizeQueryCountTest` -- `batchFetchReducesStoreQueries` measures `prepared=4`. `batchFetchReducesDepartmentQueries` measures `prepared=8`.

It is not the most optimal solution in absolute query count, but it has the **best cost-to-benefit ratio** of the entire list. It requires no code changes, produces no cartesian product, and improves *the entire project* in one shot.

| Pros | Cons |
|---|---|
| 1 line of config, global improvement | Not 1 query (it is 1 + ceil(N/batch) per type) |
| No cartesian product | Requires `@Transactional` to work |
| Greatest impact with high-cardinality ManyToOne | Less impact on OneToMany collections |

**When to use:** **always. In every Hibernate project.** It is the minimum baseline before optimizing case by case. If you can only do one thing from this list, this is it.

---

## 3b. `@BatchSize` per-collection

```java
@OneToMany
@JoinColumn(name = "department_id")
@BatchSize(size = 16)
private List<Employee> employees = new ArrayList<>();
```

Same effect as `default_batch_fetch_size=16` but with per-collection granularity. You can set `@BatchSize(size = 50)` on a large collection and `@BatchSize(size = 8)` on a small one.

For 25 departments with 6 collections x 5 items: **13 queries** (vs 176 without anything, vs 14 with global batch_fetch).

> **Test:** `BatchSizePerCollectionTest` -- 13 queries, same effect as global batch_fetch.

**Hibernate 7 note:** `@BatchSize` only works on `@OneToMany` collections. On `@ManyToOne` it is no longer supported -- use `default_batch_fetch_size` globally for ManyToOne.

| Pros | Cons |
|---|---|
| Granular control per collection | Annotation on each collection (more verbose) |
| Allows different batch sizes | Does not work on @ManyToOne (Hibernate 7) |

**When to use:** when you need different batch sizes per collection. If a single size works for everything, use `default_batch_fetch_size` globally.

---

## 3c. `@Fetch(FetchMode.SUBSELECT)`

```java
@OneToMany
@JoinColumn(name = "department_id")
@Fetch(FetchMode.SUBSELECT)
private List<Employee> employees = new ArrayList<>();
```

SUBSELECT loads ALL collections of a given type in **1 single query** using a subquery that repeats the original query:

```sql
SELECT * FROM employees WHERE department_id IN (SELECT id FROM departments)
```

Unlike `batch_fetch_size` which groups into chunks of 16, SUBSELECT always issues 1 query per collection type **regardless of the number of parents**.

| Depts | SUBSELECT | batch_fetch=16 | Without anything |
|---:|---:|---:|---:|
| 5 | 7 | 8 | 36 |
| 25 | **7** | 14 | 176 |
| 100 | **7** | 44 | 701 |

With 25 departments: SUBSELECT issues **7 queries** vs 14 for batch_fetch -- half as many. With 100 departments: **7 vs 44** -- 6x fewer. SUBSELECT does not grow with N.

> **Test:** `SubselectFetchTest` -- 7 constant queries for 5, 25, and 100 departments. Comparison with batch_fetch and without anything.

| Pros | Cons |
|---|---|
| Always 1 query per collection type | Repeats the original query as a subquery (can be expensive if the original query is complex) |
| Does not grow with N (constant) | Not globally configurable (per-collection only) |
| Better than batch for large N | Loads ALL collections, not just a batch |

**When to use:** entities with `@OneToMany` collections where you load many parents at once (findAll, listings). It is the best option when `N > 16` and batch_fetch starts making multiple rounds. **Do not use** if the original query is expensive (the subquery repeats it entirely).

---

## 3d. `@Immutable`

```java
@Entity
@Immutable
public class DepartmentReadOnly { ... }
```

Marks an entity as read-only at the Hibernate level. **Zero dirty checking**: no snapshots are created, no changes are detected, no flush occurs. Modifications are silently ignored.

The lab confirms it: modifying the name of an `@Immutable` entity without calling `save()` does not persist anything. With a normal entity, dirty checking detects it and persists at commit time.

> **Test:** `ImmutableEntityTest` -- `@Immutable` ignores modifications vs a normal entity that persists them via dirty checking.

| Pros | Cons |
|---|---|
| Zero dirty checking overhead | You cannot write with this entity |
| Less memory (no snapshots) | Modifications silently ignored (dangerous if you do not know it is immutable) |

**When to use:** **read-only** entities such as views, catalog tables, historical data. Useful for read endpoints where you know you will never modify the entity.

---

## 4. Split Queries

```java
@Transactional(readOnly = true)
public DepartmentDto getDepartmentSplitQueries(Long id) {
    Department dept = departmentRepository.findWithEmployeesById(id).orElseThrow();
    departmentRepository.findWithProjectsById(id);   // L1 cache merge
    departmentRepository.findWithBudgetsById(id);     // L1 cache merge
    departmentRepository.findWithEquipmentById(id);   // L1 cache merge
    departmentRepository.findWithPoliciesById(id);    // L1 cache merge
    departmentRepository.findWithDocumentsById(id);   // L1 cache merge
    return departmentMapper.toDto(dept);
}
```

Each query does a `JOIN FETCH` of exactly one collection. Hibernate executes **7 queries**, but since they all run within the same transaction and the same `EntityManager`, the L1 cache merges the results: the `dept` entity ends up with all its collections loaded without a cartesian product.

> **Test:** `SplitQueryTest` -- `prepared=7, entityFetch=1, collectionFetch=0, queryExec=6`. Verifies that the 6 collections (employees, projects, budgets, equipment, policies, documents) have 5 items each.

This is the strategy that Vlad Mihalcea recommends for the specific case where `@EntityGraph` would blow up (multiple collections) and `@Transactional` alone would generate N+1.

| Pros | Cons |
|---|---|
| No cartesian product | More manual code |
| No uncontrolled N+1 | 1 query per collection (predictable) |
| Full control over SQL | Coupled to the entity structure |
| L1 cache merges results automatically | Requires `JOIN FETCH` queries in the repository |

**When to use:** entities with **multiple large collections** where `@EntityGraph` throws `MultipleBagFetchException`. The typical case: `Department` with 6 collections, `Order` with `items` + `payments` + `history`.

---

## 5. DTO Projection

```java
@Transactional(readOnly = true)
public List<StoreProjection> getAllStoresProjection() {
    return entityManager.createQuery(
        "SELECT new com.example.osivlab.dto.StoreProjection(" +
            "s.id, s.name, s.address, st.name, r.name, tz.zoneId) " +
        "FROM Store s " +
        "LEFT JOIN s.storeType st " +
        "LEFT JOIN s.region r " +
        "LEFT JOIN s.timezone tz",
        StoreProjection.class)
        .getResultList();
}
```

No entities loaded. No persistence context. No dirty checking. No lazy loading. A single SQL query that returns exactly the fields you need, mapped directly to a `record`.

For 10 stores: **1 query, 0 entities loaded, 0 entity fetches**.

> **Test:** `DtoProjectionTest` -- `prepared=1, entityFetch=0, collectionFetch=0, queryExec=1`.

### DTO Projection for entities with collections

For `Department` with 6 collections, a single flat JPQL query does not work. The solution is **7 separate queries**: 1 base (native) + 6 JPQL selects that return `List<String>` directly, without loading any entity.

```java
public DepartmentProjection getDepartmentProjection(Long id) {
    Tuple base = entityManager.createNativeQuery(
        "SELECT d.id, d.name, d.code, r.name FROM departments d " +
        "LEFT JOIN regions r ON d.region_id = r.id WHERE d.id = :id", Tuple.class)
        .setParameter("id", id).getSingleResult();
    List<String> employees = entityManager.createQuery(
        "SELECT e.name FROM Employee e WHERE e.department.id = :id", String.class)
        .setParameter("id", id).getResultList();
    // ... 5 more queries for the other collections
}
```

The 7 queries are identical in count to Split Queries, but without loading entities or a persistence context. For collections of 5,000 items, DTO Projection and Split Queries have the same number of queries (7) and similar times (~60-114ms).

> **Test:** `DepartmentDtoProjectionTest` -- `prepared=7, entityFetch=0, collectionFetch=0, queryExec=7`.

| Pros | Cons |
|---|---|
| 1 query, optimal SQL | Not usable for writes |
| 0 risk of `LazyInitializationException` | More boilerplate (query + DTO/record) |
| No entities in memory, no snapshots | Each endpoint needs its own query |
| Compatible with real pagination | No dirty checking (both an advantage and a limitation) |

**When to use:** **read-only** endpoints that do not need the entity for later modification. Listings, reports, detail views without editing, high-traffic endpoints.

---

## 6. Interface Projection

```java
public interface StoreView {
    Long getId();
    String getName();
    String getAddress();
    String getStoreTypeName();
    String getRegionName();
    String getTimezoneZoneId();
}

// In the repository:
@Query("SELECT s.id AS id, s.name AS name, s.address AS address, " +
       "st.name AS storeTypeName, r.name AS regionName, tz.zoneId AS timezoneZoneId " +
       "FROM Store s LEFT JOIN s.storeType st LEFT JOIN s.region r LEFT JOIN s.timezone tz")
List<StoreView> findAllProjectedBy();
```

Same idea as DTO Projection but with less boilerplate: you declare an interface with getters and Spring Data generates the implementation. **1 query, 0 entities**, but **130 MB** for 100K records -- surprisingly heavier than @EntityGraph (118 MB). Spring Data's dynamic proxies (`java.lang.reflect.Proxy`) maintain a `Map<String, Object>` per instance, which is more expensive in memory than a plain `record` (59 MB with DTO Projection).

> **Test:** `InterfaceProjectionTest` -- `prepared=1, entityFetch=0, queryExec=1`.

| Pros | Cons |
|---|---|
| Less boilerplate than constructor DTO | Requires `@Query` with aliases |
| Spring Data generates the implementation | Does not work for collections (flat only) |
| Same efficiency as DTO Projection | Less control than an explicit record |

**When to use:** when DTO Projection is the right solution but you want less code. Ideal for simple read endpoints.

---

## 7. `Hibernate.initialize()`

```java
@Transactional(readOnly = true)
public List<StoreDto> getAllStoresWithInitialize() {
    List<Store> stores = storeRepository.findAll();
    stores.forEach(store -> {
        Hibernate.initialize(store.getStoreType());
        Hibernate.initialize(store.getRegion());
        Hibernate.initialize(store.getTimezone());
    });
    return stores.stream().map(storeMapper::toDto).toList();
}
```

Explicit control over which relationships are loaded and when. It generates the same queries as `@Transactional` (N+1), but the code makes it clear that the loading is intentional. There is no "magic" -- if a relationship is loaded, it is because you asked for it.

For 10 stores: **7 queries** (vs 1 from @EntityGraph).

> **Test:** `HibernateInitializeTest` -- `Hibernate.initialize()` 7 queries vs `@EntityGraph` 1 query.

| Pros | Cons |
|---|---|
| Explicit control over what gets loaded | Same N+1 as @Transactional |
| Self-documenting code | More verbose |
| Works with any relationship | Does not reduce queries |

**When to use:** when you need the code to be **explicit** about which relationships are loaded. Useful in complex services where `@EntityGraph` is not sufficient and you want another developer to understand what data is being loaded.

---

## 8. Memory: the hidden cost of the persistence context

With 100K stores, the `MemoryFootprintTest` and `LargeScaleAllSolutionsTest` tests measured:

| Solution | Memory | Why |
|---|---:|---|
| `@EntityGraph` | **118 MB** | Managed entities + snapshots for dirty checking |
| `@Transactional` | **108 MB** | Managed entities + snapshots (similar to EntityGraph) |
| `initialize()` | **111 MB** | Managed entities + snapshots (similar to EntityGraph) |
| Interface Projection | **130 MB** | Spring Data dynamic proxies are heavy |
| DTO Projection | **59 MB** | Plain records only, no persistence context |
| JdbcClient | **56 MB** | Plain records only, no Hibernate |

**Surprise: Interface Projection uses MORE memory than @EntityGraph** (130 MB vs 118 MB). Although Interface Projection does not load entities into the persistence context, Spring Data generates dynamic proxies for each returned row. These proxies (`java.lang.reflect.Proxy`) maintain an internal `Map<String, Object>` per instance, which is heavier than a plain `record`. At 100K records, the difference is significant.

JdbcClient and DTO Projection are the most memory-efficient options: **56-59 MB** versus 108-130 MB for the alternatives based on entities or proxies. The difference is 2x or more.

At 100K records, the difference between EntityGraph and DTO is ~60 MB. At 1M it would be ~600 MB of overhead just from using entities instead of DTOs. This is why `findAll()` with entities runs into OOM much sooner than with DTO Projection.

> **Test:** `MemoryFootprintTest` + `LargeScaleAllSolutionsTest` -- measures heap before/after loading 100K stores with each solution.

---

## Cross-cutting trade-offs

### Dirty checking is a double-edged sword

Hibernate's automatic dirty checking is convenient but dangerous. With OSIV enabled, an entity that is accidentally modified gets persisted without anyone having called `save()`. The lab demonstrates this: a service without `@Transactional` that changes an employee's password and never calls `save()` works with OSIV (because a subsequent `TransactionTemplate` triggers the auto-flush) but silently loses the change without OSIV.

> **Test:** `PasswordBugTest` -- with OSIV=true, `changePasswordBuggy()` persists the password. With OSIV=false, the password is lost. `changePasswordCorrect()` with `saveAndFlush()` works in both cases.

The solution is to use `readOnly=true` on all read transactions (disables flush) and be explicit with `save()`/`saveAndFlush()` on write transactions.

> **Test:** `ReadOnlyOptimizationTest` -- confirms that `readOnly=true` prevents persistence via dirty checking.

### LazyInitializationException is your friend

With OSIV disabled, the first error you will see is `LazyInitializationException`. It is not a bug -- it is Hibernate telling you that you tried to load data outside an open session. With OSIV enabled, that access would have worked silently, generating N+1 queries that you do not see in the logs.

> **Test:** `LazyInitExceptionTest` -- with OSIV=true, `GET /employees/{id}` (without `@Transactional`) returns 200. With OSIV=false, it throws `LazyInitializationException`. With `@Transactional` or `@EntityGraph`, it works in both cases.

---

## Volumetry: how each solution scales

All numbers in this section come from `VolumetryComparisonTest` and `VolumetryBatchFetchTest`, executed on real PostgreSQL with Testcontainers.

### Store (3 ManyToOne) -- queries by solution

| Records | @EntityGraph | @Transactional (shared) | @Transactional (unique) | batch=16 (unique) | DTO Projection |
|---:|---:|---:|---:|---:|---:|
| 100 | 1 | 7 | 301 | 22 | 1 |
| 1K | 1 | 7 | 3,001 | 190 | 1 |
| 10K | 1 | 7 | 30,001 | 1,876 | 1 |
| 100K | 1 | 7 | 300,001* | 18,751 | 1 |
| 1M | 1 | 7 | --- | --- | 1 |

> (*) 100K with unique refs without batch = 300,001 queries. Not executed because it is impractical (would take hours). With batch=16, it drops to 18,751 -- **16x constant reduction**.

`@EntityGraph` and DTO Projection stay at **1 query** up to 1M records. `@Transactional` with shared refs (catalogs) stays at **7 constant queries** up to 1M. With unique refs, it scales linearly: `1 + N*3`.

> **Test:** `LargeScaleVolumetryTest` + `LargeScaleBatchFetchTest` + `VolumetryComparisonTest`

### Store -- read times (ms)

| Records | @EntityGraph | DTO Projection | @Transactional (shared) | batch=16 (shared) | @Transactional (unique) |
|---:|---:|---:|---:|---:|---:|
| 1K | 206 | 109 | 96 | 35 | 2,134 |
| 10K | 136 | 26 | 91 | 35 | 14,737 |
| 100K | 434 | 122 | 366 | 459 | --- |
| 500K | 2,646 | 1,551 | 1,590 | 1,892 | --- |
| 1M | 5,509 | 3,276 | 3,153 | 4,655 | --- |
| **10M** | **OOM** | **OOM** | **OOM** | **OOM** | --- |

**At 10M records, all solutions fail with `OutOfMemoryError` (4GB heap)**, because no solution based on `findAll()` is designed to return millions of objects in a `List<>`. The solution at that volume is **pagination** (with the caveats of `@EntityGraph` + collections) or **streaming with `@QueryHints(FETCH_SIZE)`**.

DTO Projection is consistently the fastest: at 1M records, **3.2 seconds** versus 5.5s for @EntityGraph. The difference is the persistence context overhead: EntityGraph loads entities with snapshots for dirty checking, DTO Projection only creates flat records.

> **Test:** `LargeScaleVolumetryTest` (1K-1M) + `TenMillionTest` (10M, OOM confirmed)

### Impact of cardinality on N+1

With `@Transactional`, the number of queries **does not depend on the number of parents**, but on the number of **distinct referenced entities**:

| Records | Shared refs (2 of each type) | Unique refs (1 per store) |
|---:|---:|---:|
| 1K | **7** | 3,001 |
| 10K | **7** | 30,001 |
| 100K | **7** | 300,001 |
| 1M | **7** | 3,000,001* |

With shared refs (typical catalogs), **the N+1 does not grow** even with 1M records. With unique refs, it grows linearly and becomes impractical beyond ~10K.

> **Test:** `NPlusOneCardinalityTest` + `LargeScaleVolumetryTest`

### batch_fetch_size=16 at scale (shared refs)

| Records | Queries | Time |
|---:|---:|---:|
| 1K | 4 | 35ms |
| 10K | 4 | 35ms |
| 100K | 4 | 459ms |
| 500K | 4 | 1,892ms |
| 1M | **4** | **4,655ms** |

With shared refs, `batch_fetch_size` stays at **4 queries up to 1M records** (1 base + 1 batch per relationship type). Time grows linearly with data volume, not with query count.

> **Test:** `LargeScaleBatchFetchTest`

### Department (6 collections) -- collections are constant

| Items/collection | @Transactional | Split Queries |
|---:|---:|---:|
| 3 | 8 | 7 |
| 10 | 8 | 7 |
| 25 | 8 | 7 |
| 50 | 8 | 7 |

The number of items per collection **does not affect the query count**: Hibernate loads each collection in 1 query regardless of size. What grows is the volume of data transferred, not the number of round-trips.

> **Test:** `VolumetryComparisonTest.departmentCollectionVolumetry()`

### N departments x 5 items/collection -- horizontal scaling

| Departments | Without batch | With batch=16 | Total items |
|---:|---:|---:|---:|
| 1 | 8 | 8 | 30 |
| 5 | 32 | 8 | 150 |
| 10 | 62 | 8 | 300 |
| 25 | 152 | 14 | 750 |

**From 152 to 14 queries for 25 departments -- 91% reduction.** Without batch, the growth is `1 + N*7` (region + 6 collections). With batch=16, it stays constant up to 16 departments and increases minimally after that.

> **Test:** `VolumetryComparisonTest.multipleDepartmentsVolumetry()` + `VolumetryBatchFetchTest.departmentBatchVolumetry()`

### N departments x 5 items/col -- large scale (25 -> 5K)

| Depts | @Transactional (no batch) | With batch=16 | Reduction |
|---:|---:|---:|---:|
| 25 | 152 | 14 | 11x |
| 100 | 602 | 44 | 14x |
| 500 | 3,002 | 194 | 16x |
| 1,000 | 6,002 | 380 | 16x |
| 5,000 | 30,002 | 1,880 | 16x |

Without batch: `1 + 7N` (linear formula). With batch=16: `1 + ceil(N/16)*7` (~16x constant reduction).

At 5,000 departments without batch: **30,002 queries in 64 seconds**. With batch: **1,880 queries in 8 seconds**. An 8x factor in wall-clock time.

> **Test:** `LargeScaleDepartmentTest` + `LargeScaleDepartmentBatchTest`

### Split Queries vs DTO Projection -- scaling items per collection

| Items/col | Split Queries | DTO Projection | Total items |
|---:|---:|---:|---:|
| 5 | 7 queries, 75ms | 7 queries, 64ms | 30 |
| 50 | 7 queries, 16ms | 7 queries, 10ms | 300 |
| 500 | 7 queries, 42ms | 7 queries, 11ms | 3,000 |
| 5,000 | 7 queries, 114ms | 7 queries, 60ms | 30,000 |

Both produce **7 constant queries** regardless of collection size. DTO Projection is slightly faster because it does not load entities into the persistence context.

> **Test:** `LargeScaleDepartmentTest.splitQueriesCollectionScale()` + `LargeScaleDepartmentTest.dtoProjectionCollectionScale()`

### Definitive comparison: all solutions head-to-head

The `LargeScaleAllSolutionsTest` and `LargeScaleSubselectVsBatchTest` tests pit all solutions against each other under the same conditions, with 100K stores and up to 5K departments.

#### Store (3 ManyToOne, shared refs) -- 100K records

| Solution | Queries | Time | Memory |
|---|---:|---:|---:|
| JdbcClient | 1 | 137ms | 56 MB |
| DTO Projection | 1 | 127ms | 59 MB |
| Interface Projection | 1 | 277ms | 130 MB |
| @Transactional | 7 | 242ms | 108 MB |
| initialize() | 7 | 273ms | 111 MB |
| @EntityGraph | 1 | 369ms | 118 MB |

JdbcClient and DTO Projection are practically identical in performance and memory. Interface Projection is a negative surprise: although it issues a single query, it consumes **130 MB** -- more than @EntityGraph (118 MB) -- because Spring Data's dynamic proxies (`java.lang.reflect.Proxy` with `Map<String, Object>`) are heavier than plain records.

> **Test:** `LargeScaleAllSolutionsTest`

#### Department (6 collections x 5 items) -- scaling departments

| Depts | @Transactional | SUBSELECT | Split (1 dept) | DTO Proj (1 dept) |
|---:|---:|---:|---:|---:|
| 25 | 152q 139ms | 7q 27ms | 7q 27ms | 7q 29ms |
| 100 | 602q 351ms | 7q 23ms | 7q 11ms | 7q 8ms |
| 500 | 3,002q 1,917ms | 7q 66ms | 7q 10ms | 7q 5ms |
| 1,000 | 6,002q 4,391ms | 7q 92ms | 7q 11ms | 7q 7ms |

SUBSELECT maintains **7 constant queries** regardless of the number of departments. At 1,000 departments: 7 queries in 92ms vs 6,002 queries in 4.4 seconds -- **48x faster**.

> **Test:** `LargeScaleAllSolutionsTest`

#### SUBSELECT vs @BatchSize vs none -- the definitive collection comparison

| Depts | No batch (N+1) | @BatchSize(16) | SUBSELECT |
|---:|---:|---:|---:|
| 25 | 152q 96ms | 13q 26ms | 7q 11ms |
| 100 | 602q 336ms | 43q 49ms | 7q 15ms |
| 500 | 3,002q 2,121ms | 193q 210ms | 7q 53ms |
| 1,000 | 6,002q 4,214ms | 379q 436ms | 7q 84ms |
| 5,000 | 30,002q 45,603ms | 1,879q 3,696ms | 7q 439ms |

At 5,000 departments: **SUBSELECT issues 7 queries in 439ms** vs 30,002 queries in 45.6 seconds (no batch) -- **100x faster**. Even vs @BatchSize(16), SUBSELECT is 8x faster (439ms vs 3.7s) with 270x fewer queries (7 vs 1,879).

SUBSELECT is the **clear winner** for collections at scale. It does not grow with N, and the difference amplifies dramatically as the number of parents increases.

> **Test:** `LargeScaleSubselectVsBatchTest`

#### Memory at 100K stores -- complete ranking

| Solution | Memory |
|---|---:|
| JdbcClient | 56 MB |
| DTO Projection | 59 MB |
| @Transactional | 108 MB |
| initialize() | 111 MB |
| @EntityGraph | 118 MB |
| Interface Projection | 130 MB |

Interface Projection is the **heaviest in memory** of all measured solutions. Spring Data's dynamic proxies are more expensive than Hibernate's managed entities. If memory performance matters, use DTO Projection (record) instead of Interface Projection (proxy).

---

## Writes: what OSIV hides in modification operations

The previous sections focus on reads, but OSIV affects writes in different and more dangerous ways.

### Over-fetching: loading more data than needed

When you use `@EntityGraph` or `JOIN FETCH` to resolve a `LazyInitializationException`, you load the complete entity with ALL its columns + the full relationships -- even if you only need 2 fields. With 10K stores:

| Strategy | Columns transferred | Entities in memory |
|---|---|---|
| `@EntityGraph` (full entity) | id, name, address + ALL cols from type, region, tz | 10K Store + types + regions + tz |
| DTO Projection (6 fields) | s.id, s.name, s.address, st.name, r.name, tz.zoneId | 0 (records only) |
| Minimal projection (2 fields) | s.name, r.name | 0 |

All three issue **1 SQL query**, but the amount of data transferred and memory consumption are radically different. This matters especially on high-traffic endpoints.

> **Test:** `OverFetchingTest` -- 3 tests comparing the same dataset with @EntityGraph, DTO Projection, and minimal 2-column projection.

### Cascade persist: the cost of saving complete graphs

Saving a `Department` with 6 collections via `CascadeType.ALL` generates `2 + 6N` statements (1 region + 1 dept + N items per collection):

| Items/col | Statements | Time | Total items |
|---:|---:|---:|---:|
| 10 | 62 | 38ms | 60 |
| 50 | 302 | 155ms | 300 |
| 100 | 602 | 305ms | 600 |
| 500 | 3,002 | 1,487ms | 3,000 |

> **Test:** `CascadePersistTest` -- measures statements and time with and without `jdbc.batch_size`.

### jdbc.batch_size does NOT work with IDENTITY generation

A lab finding: configuring `hibernate.jdbc.batch_size=50` **does not reduce the number of statements** when using `@GeneratedValue(strategy = GenerationType.IDENTITY)` (the default in PostgreSQL with auto-increment). Hibernate needs the generated ID back immediately after each INSERT, which prevents grouping multiple INSERTs into a single JDBC batch.

| Volume | Without batch | With batch=50 |
|---:|---:|---:|
| 100 orders | 100 stmts, 143ms | 100 stmts, 92ms |
| 1K orders | 1,000 stmts, 1s | 1,000 stmts, 934ms |
| 5K orders | 5,000 stmts, 4.8s | 5,000 stmts, 4.5s |

**Statements identical.** For `jdbc.batch_size` to work, you need `GenerationType.SEQUENCE` with `@SequenceGenerator(allocationSize = 50)`. This allows Hibernate to pre-allocate IDs in blocks and batch the INSERTs.

> **Test:** `BulkInsertTest` -- compares bulk insert with and without batch, confirming that IDENTITY prevents batching.

### Optimistic locking and OSIV

With `@Version` on the entity, Hibernate detects concurrent modifications. Without OSIV, detection is **early** -- inside the `@Transactional` where you can handle the error. With OSIV, detection can be **late** -- at the end of the request when the automatic flush tries to persist the stale entity, where you can no longer do a clean rollback.

> **Test:** `OptimisticLockingTest` -- simulates two users modifying the same `Order`. The second receives `StaleObjectStateException`.

### Accidental dirty checking (the password bug)

Without OSIV, if you modify an entity without calling `save()`, the change is silently lost (the entity is detached). With OSIV, the dirty checking at the end of the request detects the modification and persists it -- which seems "correct" but masks the lack of an explicit `save()`.

> **Test:** `PasswordBugTest` -- with OSIV=true the password is persisted without `save()`. With OSIV=false it is lost. `saveAndFlush()` works in both cases.

---

## JPA/Hibernate vs alternatives: is it a tooling problem?

The OSIV problem **only exists because Hibernate manages the session and lazy loading for you**. If you use a different tool to access the database, the problems disappear -- but so do the conveniences.

### Tool and problem map

| Tool | Lazy loading? | OSIV applies? | N+1 possible? | Dirty checking? |
|---|:---:|:---:|:---:|:---:|
| **JPA/Hibernate** | Yes (proxies) | Yes | Yes | Yes |
| **Spring JdbcClient** | No | No | No | No |
| **jOOQ** | No | No | No | No |
| **MyBatis** | Configurable | No | Configurable | No |
| **Spring Data R2DBC** | No | No | No | No |
| **Native queries via JPA** | No (flat result) | Session still open | No | Yes (if you touch entities) |

With JdbcClient (or JdbcTemplate, jOOQ, etc.) you write the SQL yourself: no proxies, no persistent session, no lazy loading, no accidental dirty checking. You control exactly what data is loaded and when.

### JPA DTO Projection vs JdbcClient -- same query, same result

Both execute the same SQL query and return the same `StoreProjection`. The difference is the overhead:

| Records | JPA DTO Projection | JdbcClient | Difference |
|---:|---:|---:|---|
| 1K | 96ms | 9ms | JDBC 10x faster |
| 10K | 22ms | 21ms | Similar |
| 100K | 159ms | 93ms | JDBC 1.7x faster |
| 500K | 1,437ms | 1,430ms | Similar |

At low record counts (cold start), JPA has query parser initialization overhead. At high volume, the difference fades because the bottleneck is data transfer, not the framework.

> **Test:** `JpaVsJdbcTest.jpaVsJdbcProjection()` -- same query, same data, two different paths.

### @EntityGraph (full entity) vs JdbcClient (minimal DTO) -- real overhead

This is where the difference shows: @EntityGraph loads full entities with persistence context and snapshots for dirty checking. JdbcClient returns flat DTOs with no overhead.

| Records | @EntityGraph | JdbcClient | Ratio |
|---:|---:|---:|---|
| 1K | 111ms | 6ms | **18.5x** slower |
| 10K | 93ms | 34ms | **2.7x** slower |
| 100K | 542ms | 275ms | **2.0x** slower |

At 1K records, the persistence context overhead (creating managed entities, snapshots for dirty checking, L1 cache) is **18x** more expensive than mapping rows to flat records. At 100K the difference shrinks to 2x because data transfer dominates.

> **Test:** `JpaVsJdbcTest.entityGraphVsJdbc()` -- same data, @EntityGraph vs JdbcClient.

### What you lose when leaving JPA

| What you lose | Impact |
|---|---|
| Automatic dirty checking | You must issue an explicit UPDATE for every change |
| Cascade persist/merge/remove | You must manage relationships manually |
| L1 cache (persistence context) | Repeated queries for the same ID hit the database |
| Declarative `@EntityGraph` | You write the JOINs by hand |
| Entity inheritance | Manual mappings per type |
| Optimistic locking (`@Version`) | You implement it yourself with WHERE version = ? |
| Spring Data repositories | You write the queries yourself |

### When it is worth leaving JPA

- **Read-only endpoints with high traffic**: JdbcClient + DTO is faster and simpler
- **Reports and aggregations**: native SQL is more expressive than JPQL for GROUP BY, window functions, CTEs
- **Bulk operations**: mass INSERT/UPDATE is orders of magnitude faster without persistence context
- **Stateless microservices**: if you do not need dirty checking or cascade, JPA is unnecessary overhead

### When JPA remains the best choice

- **CRUD with complex relationships**: cascade and dirty checking save a lot of boilerplate
- **Domains with business logic in entities**: DDD with rich domain models
- **Large teams**: JPA/Spring Data conventions reduce code variability

---

## Decision tree

```
0. ALWAYS: configure batch_fetch_size=16 as a global baseline
   (Test: BatchSizeQueryCountTest -- reduces 31 queries to 4)

1. Is it a WRITE operation?
   YES --> @Transactional + explicit save()/saveAndFlush()
           NEVER rely on implicit dirty checking
           For batch inserts: use GenerationType.SEQUENCE, not IDENTITY
           (Test: PasswordBugTest -- without save() the change is lost with OSIV=false)
           (Test: BulkInsertTest -- IDENTITY prevents JDBC batching)
   NO  --> (it is a read, continue)

2. How many records do you expect to return?

   THOUSANDS OR MORE (>1K) --> pagination or streaming is MANDATORY
       findAll() with >10K records is a memory time bomb
       At 1M: all solutions take 3-5s loading into List<>
       At 10M: ALL fail with OutOfMemoryError (4GB heap)
       (Test: LargeScaleVolumetryTest -- OOM at 10M, 5.5s at 1M)
       (Test: TenMillionTest -- OOM confirmed for EntityGraph, DTO, and Transactional)

       Will you paginate with collections?
         YES --> DO NOT use @EntityGraph (paginates IN MEMORY, no LIMIT/OFFSET)
                 Use @Transactional(readOnly=true) + batch_fetch
                 (Test: PaginationTrapTest -- 10 depts loaded to show 3)
         NO  --> Paginating with ManyToOne works fine with any solution

   HUNDREDS OR FEWER --> findAll() is viable, continue

3. Are the ManyToOne refs shared (catalogs) or unique (1 per entity)?

   SHARED (types, categories, regions)
       --> N+1 is constant: 7 queries even with 1M records
           batch_fetch does not help much here (from 7 to 4)
           (Test: LargeScaleVolumetryTest -- 7 constant queries up to 1M)

   UNIQUE (createdBy, assignedTo, etc.)
       --> N+1 grows linearly: 3,001 queries for 1K, 30,001 for 10K
           WITHOUT batch_fetch: impractical beyond 10K (14.7s for 10K)
           WITH batch_fetch=16: 16x constant reduction (190 queries for 1K)
           (Test: NPlusOneCardinalityTest -- 7 vs 31)
           (Test: LargeScaleBatchFetchTest -- 16x reduction up to 100K)

4. Do you need the JPA entity or just flat data?

   JUST DATA --> DTO Projection or Interface Projection
       Store (ManyToOne): 1 query, 0 entities, 56 MB for 100K
       (Test: DtoProjectionTest -- prepared=1, entityFetch=0)
       (Test: InterfaceProjectionTest -- same efficiency, less boilerplate)
       Department (collections): 7 queries (1 base + 6 selects), 0 entities
       (Test: DepartmentDtoProjectionTest -- prepared=7, entityFetch=0)
       Less over-fetching: only transfers the columns you need
       (Test: OverFetchingTest -- @EntityGraph 118 MB vs DTO 59 MB for 100K)
       Alternative without JPA: JdbcClient (2-18x faster than @EntityGraph)
       (Test: JpaVsJdbcTest -- same query, less overhead)

   NEED ENTITY --> Is it read-only?
       YES --> Consider @Immutable (no dirty checking, no snapshots)
               (Test: ImmutableEntityTest -- modifications ignored)
       NO  --> (continue)

5. Does the entity have multiple collections (>1 List/Set)?

   YES --> How many parents do you load at once?

       1 SINGLE PARENT (findById) --> Split Queries
           7 constant queries from 5 to 5,000 items per collection
           (Test: SplitQueryTest -- 7 queries, 0 cartesian product)
           (Test: LargeScaleDepartmentTest -- 7 constant queries up to 5K items/col)

       MANY PARENTS (findAll) --> @Fetch(FetchMode.SUBSELECT)
           Always 7 queries (1 base + 6 subselects) REGARDLESS OF N
           Better than batch_fetch when N > 16
           (Test: SubselectFetchTest -- 7 queries for 5, 25, and 100 departments)
           Alternative: @BatchSize(16) per-collection (same effect as global)
           (Test: BatchSizePerCollectionTest -- 13 queries for 25 depts)

       NEVER @EntityGraph with multiple collections:
         - With List: MultipleBagFetchException
         - With Set: silent cartesian product (5^3 = 125 rows for 15 items)
         (Test: CartesianProductTest + CartesianProductWithSetsTest)

   NO  --> (continue)

6. How many ManyToOne relationships does it have?

   1-3 ManyToOne --> @EntityGraph (1 JOIN query)
                     Works well up to 1M records (5.5s at 1M)
                     Beware of over-fetching: loads all columns (118 MB vs 59 MB)
                     (Test: EntityGraphQueryCountTest -- prepared=1)
                     (Test: MemoryFootprintTest + LargeScaleAllSolutionsTest -- 118 MB vs 59 MB for 100K)

   Many or deep --> @Transactional(readOnly=true) + batch_fetch
                    (Test: LargeScaleBatchFetchTest -- 16x reduction)

ALWAYS on read methods: @Transactional(readOnly=true)
  - Disables dirty checking (no accidental flush)
  - Reduces memory (no state snapshots)
  (Test: ReadOnlyOptimizationTest -- modifications are not persisted)
```

---

## Executive summary

### Reads -- the winning combination

1. **`batch_fetch_size=16`** as a global baseline (1 line of yml -- improves everything)
2. **`@Fetch(FetchMode.SUBSELECT)`** for collections when loading many parents (7 constant queries regardless of N)
3. **Split Queries** for a single entity with multiple collections
4. **`@EntityGraph`** for simple entities with 1-3 ManyToOne (1 query, but 118 MB vs 59 MB at 100K)
5. **DTO Projection** for read-only endpoints (59 MB, fastest at scale). **Interface Projection** is convenient but uses 130 MB (dynamic proxies)
6. **JdbcClient** when you don't need JPA (2-18x faster than @EntityGraph)
7. **`@Immutable`** for read-only entities (no dirty checking, no snapshots)
8. **`readOnly=true`** always on read transactions (less memory, no accidental flush)

### Writes

1. **Always explicit `@Transactional`** -- never rely on OSIV to persist changes
2. **Always explicit `save()`/`saveAndFlush()`** -- never rely on dirty checking
3. **`GenerationType.SEQUENCE`** if you need batch inserts -- `IDENTITY` prevents JDBC batching
4. **`StatelessSession`** for bulk writes without persistence context
5. **`@Version`** for optimistic locking -- without OSIV detection is early and manageable

### Volumetry

- At **>1K records**: pagination is mandatory
- At **>10K with unique refs without batch**: N+1 becomes impractical
- At **1M**: all solutions based on `findAll()` take 3-5s. @EntityGraph 118 MB vs DTO 59 MB
- At **10M**: `OutOfMemoryError` for all solutions -- you need streaming or pagination
- **`@Fetch(SUBSELECT)`**: 7 constant queries from 5 to 5,000 departments (100x faster than N+1 at 5K depts)
- **Interface Projection**: 130 MB at 100K -- heavier than @EntityGraph (118 MB) due to dynamic proxies
- **SUBSELECT vs @BatchSize(16) at 5K depts**: 7q/439ms vs 1,879q/3.7s -- SUBSELECT wins by 8x

### When you DON'T need JPA

- Pure reads with high traffic --> **JdbcClient** (2-18x faster)
- Reports and aggregations --> native SQL
- Bulk writes --> **StatelessSession** or native SQL with `generate_series`

Every number in this document is backed by a passing test. The full code is in the `open-in-view-lab` repository.
