# open-in-view-lab

A laboratory to reproduce, measure, and fix the problems caused by Spring Boot's **Open-in-View (OSIV)** anti-pattern. Every claim in the companion blog article is backed by a test that passes green.

## Stack

| Component        | Version / Detail                  |
|------------------|-----------------------------------|
| Java             | 25                                |
| Spring Boot      | 4.0.0                            |
| Database         | PostgreSQL 16 + Testcontainers   |
| ORM              | Hibernate 7                       |
| Connection Pool  | HikariCP                         |
| Mapping          | MapStruct                        |
| Concurrency      | Virtual Threads enabled           |

## How to run

### Prerequisites

- Java 25+
- Docker running (required by Testcontainers)

### Run all tests

```bash
./mvnw test
```

No external database needed — Testcontainers spins up a PostgreSQL container automatically.

### Local development

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

## Test modules

### OSIV problems

| Test | Description |
|---|---|
| `deadlock/DeadlockReproductionTest` | Connection pool deadlock with OSIV + `TransactionTemplate` + virtual threads |
| `lazyinit/LazyInitExceptionTest` | `LazyInitializationException` with OSIV on vs off, `@Transactional` and `@EntityGraph` fixes |
| `dirtychecking/PasswordBugTest` | OSIV auto-flush hides missing `save()` — password persisted without explicit save |

### Read solutions (query count)

| Test | Description |
|---|---|
| `solutions/EntityGraphQueryCountTest` | `@EntityGraph`: 1 JOIN query for ManyToOne relations |
| `solutions/EntityGraphHappyPathTest` | `@EntityGraph` works fine with ManyToOne + 1 single collection |
| `solutions/TransactionalQueryCountTest` | `@Transactional(readOnly=true)`: N+1 queries within transaction |
| `solutions/BatchSizeQueryCountTest` | `default_batch_fetch_size=16`: reduces N+1 by 16x |
| `solutions/BatchSizePerCollectionTest` | `@BatchSize(16)` per-collection: same as global but fine-grained |
| `solutions/SubselectFetchTest` | `@Fetch(SUBSELECT)`: constant 7 queries regardless of parent count |
| `solutions/SplitQueryTest` | Split Queries: 1 JOIN FETCH per collection, L1 cache merge |
| `solutions/DtoProjectionTest` | DTO Projection (JPQL constructor): 1 query, 0 entities |
| `solutions/DepartmentDtoProjectionTest` | DTO Projection for collections: 7 queries, 0 entities |
| `solutions/InterfaceProjectionTest` | Interface Projection: 1 query, less boilerplate than constructor DTO |
| `solutions/HibernateInitializeTest` | `Hibernate.initialize()`: explicit lazy loading (7 queries, full control) |
| `solutions/ImmutableEntityTest` | `@Immutable`: zero dirty checking, modifications silently ignored |

### Trade-offs and traps

| Test | Description |
|---|---|
| `solutions/CartesianProductTest` | `@EntityGraph` + multiple `List` = `MultipleBagFetchException` |
| `solutions/CartesianProductWithSetsTest` | `@EntityGraph` + `Set` = silent cartesian product (125 rows for 15 items) |
| `solutions/PaginationTrapTest` | `@EntityGraph` + `Pageable` + collection = in-memory pagination |
| `solutions/NPlusOneCardinalityTest` | N+1 depends on distinct referenced entities (7 vs 31 queries) |
| `solutions/ReadOnlyOptimizationTest` | `readOnly=true` disables dirty checking and reduces memory |
| `solutions/OverFetchingTest` | `@EntityGraph` loads all columns vs DTO/JDBC minimal fetch |
| `solutions/MemoryFootprintTest` | Memory: @EntityGraph 118 MB vs DTO 59 MB vs JDBC 56 MB at 100K |

### JPA vs alternatives

| Test | Description |
|---|---|
| `solutions/JpaVsJdbcTest` | JPA DTO Projection vs JdbcClient: same query, 2-18x overhead difference |

### Volumetry (scaling)

| Test | Description |
|---|---|
| `solutions/VolumetryComparisonTest` | Small scale (5-100): all solutions compared |
| `solutions/VolumetryBatchFetchTest` | Small scale with `batch_fetch_size=16` |
| `solutions/LargeScaleVolumetryTest` | 1K-1M: `@EntityGraph`, DTO, `@Transactional`, JdbcClient |
| `solutions/LargeScaleBatchFetchTest` | 1K-1M: `batch_fetch_size=16` with unique/shared refs |
| `solutions/LargeScaleDepartmentTest` | 25-5K depts: `@Transactional`, Split Queries, DTO Projection |
| `solutions/LargeScaleDepartmentBatchTest` | 25-5K depts: `batch_fetch_size=16` |
| `solutions/LargeScaleSubselectVsBatchTest` | 25-5K depts: SUBSELECT vs @BatchSize vs none |
| `solutions/LargeScaleAllSolutionsTest` | Definitive: ALL solutions head-to-head + memory comparison |
| `solutions/TenMillionTest` | 10M records: OOM for all solutions |

### Benchmarks (warmup + median)

| Test | Description |
|---|---|
| `solutions/BenchmarkStoreTest` | 6 Store solutions at 1K/10K/100K (warmup=2, runs=5, median) |
| `solutions/BenchmarkDepartmentTest` | SUBSELECT vs @BatchSize vs N+1 + Split vs DTO (warmup=2, runs=5) |
| `write/BenchmarkWriteTest` | Cascade, bulk insert, StatelessSession (warmup=2, runs=5) |

### Write solutions

| Test | Description |
|---|---|
| `write/CascadePersistTest` | Cascade persist with and without `jdbc.batch_size` |
| `write/BulkInsertTest` | Bulk insert: `jdbc.batch_size` has no effect with `IDENTITY` |
| `write/StatelessSessionTest` | `StatelessSession` vs `Session` for bulk writes |
| `write/OptimisticLockingTest` | `@Version` detects concurrent modification |

## Key findings

### Reads
- **`@EntityGraph`**: 1 query for ManyToOne. Fails with >1 `List` (`MultipleBagFetchException`), silent cartesian with `Set`, in-memory pagination with collections. 118 MB at 100K.
- **`@Transactional`**: N+1 depends on cardinality — 7 queries with shared refs (constant to 1M), 300K queries with unique refs at 100K.
- **`batch_fetch_size=16`**: 16x query reduction. Best cost/benefit for ManyToOne.
- **`@Fetch(SUBSELECT)`**: **winner for collections** — constant 7 queries from 25 to 5K departments. 100x faster than N+1 at 5K depts (439ms vs 45.6s).
- **Split Queries**: 7 constant queries for single entity with multiple collections.
- **DTO Projection**: 1 query (or 7 for collections), 59 MB at 100K. Fastest at scale.
- **Interface Projection**: 1 query but **130 MB at 100K** — heavier than @EntityGraph due to dynamic proxies.
- **JdbcClient**: 1 query, 56 MB. 2-18x faster than @EntityGraph. No Hibernate overhead.
- **`@Immutable`**: zero dirty checking, modifications silently ignored.
- **`readOnly=true`**: prevents accidental dirty checking flush, reduces memory.

### Writes
- **`jdbc.batch_size`**: does NOT work with `GenerationType.IDENTITY`. Need `SEQUENCE`.
- **`batch_size=50` with IDENTITY**: slightly SLOWER than no batch (overhead without benefit).
- **`StatelessSession` vs `Session`**: practically identical with `IDENTITY`.

### Volumetry
- At **1M**: all solutions take 3-5s. @EntityGraph 118 MB vs DTO 59 MB.
- At **10M**: ALL solutions OOM with `findAll()` (4GB heap).
- At **5K depts**: SUBSELECT 7q/439ms vs @BatchSize 1,879q/3.7s vs none 30,002q/45.6s.

### OSIV is JPA-specific
- JdbcClient, jOOQ, MyBatis don't have lazy loading, sessions, or dirty checking — no OSIV problems.

## Companion article

[Blog article](https://TODO) — see also `COMPARATIVA-SOLUCIONES.md` (Spanish) and `SOLUTION-COMPARISON.md` (English).
