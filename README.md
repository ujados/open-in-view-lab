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

| Module / Suite | Description |
|---|---|
| `deadlock/` | Connection pool deadlock with OSIV + `TransactionTemplate` + virtual threads |
| `lazyinit/` | `LazyInitializationException` with OSIV on vs off |
| `solutions/` | Query count comparison: `@EntityGraph`, `@Transactional`, `batch_fetch_size`, Split Queries, DTO Projection |
| `solutions/` (volumetry) | Scaling tests from 100 to 1M records, OOM at 10M |
| `solutions/` (cartesian) | `MultipleBagFetchException` with `List`, silent cartesian product with `Set` |
| `solutions/` (pagination) | `@EntityGraph` + `Pageable` = in-memory pagination trap |
| `solutions/` (over-fetching) | `@EntityGraph` loads all columns vs DTO minimal fetch |
| `solutions/` (N+1 cardinality) | N+1 depends on distinct referenced entities, not parent count |
| `solutions/SubselectFetchTest` | `@Fetch(SUBSELECT)`: constant 7 queries regardless of parent count |
| `solutions/BatchSizePerCollectionTest` | `@BatchSize` per-collection: same as global but fine-grained |
| `solutions/ImmutableEntityTest` | `@Immutable`: zero dirty checking, modifications silently ignored |
| `solutions/` (readOnly) | `readOnly=true` disables dirty checking |
| `dirtychecking/` | Password bug: OSIV auto-flush hides missing `save()` |
| `solutions/JpaVsJdbcTest` | JPA/Hibernate vs JdbcClient: same query, different overhead (1K-500K) |
| `write/` | Cascade persist, bulk insert, `jdbc.batch_size`, optimistic locking |

## Key findings

- **`@EntityGraph`**: 1 query, but `MultipleBagFetchException` with >1 `List` collection, cartesian product with `Set`, and in-memory pagination.
- **`@Transactional`**: N+1 depends on cardinality (7 queries with shared refs, 300K with unique refs at 100K records).
- **`batch_fetch_size=16`**: 16x query reduction, best cost/benefit. 4 queries constant up to 1M records with shared refs.
- **Split Queries**: 7 queries constant regardless of collection size (5 to 5,000 items).
- **DTO Projection**: always 1 query (or 7 for collections), no entities in memory, fastest at scale.
- **`@Fetch(FetchMode.SUBSELECT)`**: 7 queries constant for any N (vs batch_fetch's ceil(N/16)*7) -- best for large findAll with collections.
- **`@Immutable`** entities skip dirty checking entirely -- less memory, but modifications silently ignored.
- **10M records**: ALL solutions OOM with `findAll()` — pagination or streaming required.
- **`jdbc.batch_size`**: does NOT work with `GenerationType.IDENTITY`.
- **`readOnly=true`**: prevents accidental dirty checking flush.
- **JPA DTO Projection vs JdbcClient**: similar performance at high volume (500K), but JdbcClient 10x faster at 1K due to Hibernate query parser overhead.
- **`@EntityGraph` vs JdbcClient**: 2-18x slower due to persistence context + dirty checking snapshots.
- **OSIV problems are JPA/Hibernate-specific** — JdbcClient, jOOQ, MyBatis don't have lazy loading, sessions, or dirty checking.

## Companion article

[Blog article](https://TODO) — see also `COMPARATIVA-SOLUCIONES.md` (Spanish) and `SOLUTION-COMPARISON.md` (English).
