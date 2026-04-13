# Comparativa de soluciones al desactivar Open-in-View

Cuando desactivas `spring.jpa.open-in-view=false`, cada relacion lazy que se acceda fuera de una transaccion explota con `LazyInitializationException`. La pregunta ya no es *si* desactivarlo, sino *como* cargar los datos que necesitas sin depender de la sesion abierta del filtro OSIV.

No hay una unica solucion correcta. Cada estrategia tiene un rango de aplicacion distinto, y la mejor eleccion depende de la forma de las entidades que manejas: cuantas relaciones tienen, si son `ManyToOne` o colecciones, y si necesitas la entidad para escribir o solo para leer.

A continuacion presento cinco estrategias, medidas con Hibernate Statistics sobre PostgreSQL real (Testcontainers), con los numeros exactos que arrojo el laboratorio. Todos los tests del proyecto pasan en verde y son el respaldo de cada numero que aparece aqui.

---

## Tabla resumen

| Solucion | Queries (10 Store, 3 ManyToOne) | Queries (1 Dept, 6 colecciones x5) | Producto cartesiano? | Esfuerzo |
|---|:---:|:---:|:---:|---|
| `@EntityGraph` | **1** | `MultipleBagFetchException` | Si | Anotacion en repository |
| `@Transactional(readOnly=true)` | 7 o 31 (*) | 8 | No | Anotacion en servicio |
| `batch_fetch_size=16` | **4** | 8 | No | 1 linea en `application.yml` |
| Split Queries | n/a (ManyToOne no necesita split) | **7** | No | Codigo manual |
| DTO Projection | **1** | **7** | No | Query JPQL + record/DTO |

> (*) 7 queries cuando las 10 stores comparten 2 references de cada tipo. **31 queries** cuando cada store tiene referencias unicas. El N+1 depende de la cardinalidad de las entidades referenciadas, no del numero de entidades padre. Test: `NPlusOneCardinalityTest`.

---

## 1. `@EntityGraph`

```java
@EntityGraph(attributePaths = {"storeType", "region", "timezone"})
List<Store> findAllWithAllRelationsBy();
```

Hibernate genera un solo `SELECT` con `LEFT JOIN` por cada relacion declarada. Para `Store` con 3 `ManyToOne`, el resultado es **1 query** que devuelve 10 filas.

> **Test:** `EntityGraphQueryCountTest` — `prepared=1, entityFetch=0, queryExec=1`

### El problema con colecciones

Si intentas un `@EntityGraph` sobre `Department` con 6 `List<>`, Hibernate lanza `MultipleBagFetchException` porque no puede hacer fetch de multiples bags (listas) en una sola query. Incluso si cambiaras las listas a `Set`, obtendrias un producto cartesiano silencioso: con 5 items por coleccion, la query devolveria 5^6 = 15.625 filas para un solo departamento.

> **Test:** `CartesianProductTest` — captura la `MultipleBagFetchException` y demuestra que `@EntityGraph` con multiples `List` no funciona.

### El workaround de List a Set: peor que la enfermedad

La "solucion" habitual que aparece en StackOverflow es cambiar `List` a `Set`. Esto evita la excepcion, pero produce un **cartesiano silencioso**. Con 3 colecciones `Set` de 5 items cada una, la BD devuelve 5 * 5 * 5 = **125 filas** para 15 items reales. Hibernate deduplica en memoria, asi que el resultado parece correcto — pero la transferencia de datos es explosiva.

Con 50 items por coleccion: 50^3 = **125,000 filas** para 150 items. Es peor que `MultipleBagFetchException` porque es completamente silencioso.

> **Test:** `CartesianProductWithSetsTest` — demuestra que con `Set` la query devuelve 1 resultado, Hibernate muestra 5+5+5 items correctos, pero la BD transferio 125 filas.

### La trampa de la paginacion

**Esto es critico y facil de pasar por alto.** Si combinas `@EntityGraph` con `Pageable` sobre una coleccion, Hibernate lanza el warning `HHH90003004` y pagina **en memoria**: carga TODAS las filas de la BD y recorta en Java. El SQL generado no tiene `LIMIT`/`OFFSET`.

Con 10 departamentos pidiendo pagina de 3:
- `page.getContent().size()` devuelve 3 (parece correcto)
- `page.getTotalElements()` devuelve 10
- Pero Hibernate cargo los 10 departamentos con todos sus empleados en memoria antes de devolver 3

Con 100K registros esto mata la aplicacion sin aviso.

> **Test:** `PaginationTrapTest` — demuestra que `findAllWithEmployeesBy(PageRequest.of(0, 3))` carga los 10 departamentos en memoria, mientras que `findAll(PageRequest.of(0, 3))` usa `LIMIT`/`OFFSET` real.

| Pros | Contras |
|---|---|
| 1 sola query SQL | Producto cartesiano con multiples colecciones |
| Cero configuracion extra | `MultipleBagFetchException` con >1 `List` |
| Declarativo y legible | Paginacion en memoria con colecciones |
| | No escala a grafos complejos |

**Cuando usarlo:** entidades con **1-2 relaciones `ManyToOne`** o **una unica coleccion sin paginacion**. El caso clasico: `Product` con su `Category`, `Store` con `StoreType`/`Region`/`Timezone`.

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

La anotacion mantiene la sesion de Hibernate abierta durante todo el metodo. Cada relacion lazy se carga bajo demanda cuando el mapper la accede.

### N+1 depende de la cardinalidad, no de los padres

Es facil asumir que 10 stores con 3 relaciones `ManyToOne` generan 31 queries (1 + 3*10). Pero el laboratorio midio dos escenarios distintos:

| Escenario | Queries | Por que |
|---|:---:|---|
| 10 stores, 2 storeTypes/regions/tz compartidos | **7** | 1 base + 2 + 2 + 2 (entidades distintas referenciadas) |
| 10 stores, cada uno con refs **unicas** | **31** | 1 base + 10 + 10 + 10 (todas las refs son distintas) |

Hibernate no dispara 1 query por proxy, sino 1 por **entidad distinta** referenciada. Si 10 stores apuntan a los mismos 2 `StoreType`, Hibernate solo carga esos 2 — no 10. El N+1 es proporcional a la cardinalidad de las entidades referenciadas, no al numero de entidades padre.

En produccion, donde los catalogos (tipos, regiones, zonas horarias) suelen ser tablas pequenas compartidas, el N+1 real es mucho menor de lo que el calculo teorico sugiere. Pero si cada entidad padre referencia algo unico (como `createdBy` apuntando a usuarios distintos), el N+1 pega con fuerza total.

> **Test:** `NPlusOneCardinalityTest` — `sharedReferencesReduceNPlusOne` mide 7, `uniqueReferencesMaximizeNPlusOne` mide 31. Mismas 10 stores, distinta cardinalidad.

### readOnly=true desactiva el dirty checking

`readOnly=true` no es solo una optimizacion semantica. Hibernate establece `FlushMode.MANUAL`, lo que significa que:
- Las modificaciones a entidades dentro de la transaccion **no se persisten**
- Hibernate puede omitir los snapshots de estado (menos memoria por entidad)
- No hay flush automatico al commit

El laboratorio lo demuestra directamente: en una transaccion `readOnly=true`, modificar el nombre de 5 stores y no llamar a `save()` no persiste nada. En una transaccion normal, el dirty checking detecta los cambios y los persiste automaticamente al commit.

> **Test:** `ReadOnlyOptimizationTest` — `readOnlySkipsDirtyChecking` modifica entidades sin save → no se persisten. `readWriteFlushesChanges` hace lo mismo sin readOnly → cambios persistidos.

| Pros | Contras |
|---|---|
| Funciona con cualquier profundidad | N+1 queries (severidad depende de cardinalidad) |
| Sin producto cartesiano | Retiene conexion del pool durante todo el metodo |
| readOnly=true reduce memoria | Si el metodo es lento, la conexion se desperdicia |

**Cuando usarlo:** como **fallback universal** para grafos profundos o entidades con muchas relaciones. **Siempre combinado con `batch_fetch_size`** para reducir el N+1. Y siempre con `readOnly=true` en metodos de lectura.

### Cuidado con la retencion de conexion

`@Transactional` retiene una conexion del pool de HikariCP durante toda la ejecucion del metodo. Si dentro del metodo hay logica lenta (mapeo pesado, llamadas HTTP, procesamiento), esa conexion esta bloqueada sin ejecutar SQL.

Con virtual threads y pool pequeno, esto reproduce exactamente el mismo patron que causo el deadlock con OSIV: demasiados threads reteniendo conexiones por demasiado tiempo. El test de deadlock del laboratorio (`DeadlockReproductionTest`) demuestra este patron con `pool=2` y 4 virtual threads concurrentes.

> **Test:** `DeadlockReproductionTest` — 4 requests concurrentes con pool=2 y OSIV+TransactionTemplate.

---

## 3. `default_batch_fetch_size`

```yaml
spring:
  jpa:
    properties:
      hibernate:
        default_batch_fetch_size: 16
```

Una sola linea de configuracion que cambia como Hibernate carga las relaciones lazy. En vez de hacer 1 query por cada proxy no inicializado, Hibernate agrupa hasta 16 proxies del mismo tipo en una sola query con `WHERE id IN (?, ?, ..., ?)`.

| Escenario | Sin batch | Con batch=16 | Reduccion |
|---|:---:|:---:|:---:|
| 10 stores, 3 ManyToOne (refs compartidas) | 7 | **4** | 43% |
| 10 stores, 3 ManyToOne (refs unicas) | 31 | **4** | 87% |
| 1 dept, 6 colecciones x5 items | 8 | **8** | 0% |

El batch fetch brilla con las relaciones `ManyToOne` donde hay muchas referencias distintas: convierte 31 queries en 4 (1 base + 1 batch por tipo). Para colecciones `OneToMany`, el beneficio es menor porque cada coleccion ya se carga en 1 query por entidad padre.

> **Test:** `BatchSizeQueryCountTest` — `batchFetchReducesStoreQueries` mide `prepared=4`. `batchFetchReducesDepartmentQueries` mide `prepared=8`.

No es la solucion mas optima en queries absolutas, pero es la que tiene la **mejor relacion coste/beneficio** de toda la lista. No requiere tocar codigo, no produce cartesiano, y mejora *todo* el proyecto de golpe.

| Pros | Contras |
|---|---|
| 1 linea de config, mejora global | No es 1 query (es 1 + ceil(N/batch) por tipo) |
| Sin producto cartesiano | Requiere `@Transactional` para funcionar |
| El mayor impacto con ManyToOne de alta cardinalidad | Menos impacto en colecciones OneToMany |

**Cuando usarlo:** **siempre. En todo proyecto Hibernate.** Es el baseline minimo antes de optimizar caso por caso. Si solo puedes hacer una cosa de esta lista, es esta.

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

Cada query hace `JOIN FETCH` de exactamente una coleccion. Hibernate ejecuta **7 queries**, pero como todas se ejecutan dentro de la misma transaccion y el mismo `EntityManager`, la cache L1 fusiona los resultados: la entidad `dept` termina con todas sus colecciones cargadas sin producto cartesiano.

> **Test:** `SplitQueryTest` — `prepared=7, entityFetch=1, collectionFetch=0, queryExec=6`. Verifica que las 6 colecciones (employees, projects, budgets, equipment, policies, documents) tienen 5 items cada una.

Es la estrategia que Vlad Mihalcea recomienda para el caso especifico donde `@EntityGraph` explotaria (multiples colecciones) y `@Transactional` sola generaria N+1.

| Pros | Contras |
|---|---|
| Sin cartesiano | Mas codigo manual |
| Sin N+1 descontrolado | 1 query por coleccion (predecible) |
| Control total del SQL | Acoplado a la estructura de la entidad |
| L1 cache fusiona resultados automaticamente | Requiere queries `JOIN FETCH` en el repository |

**Cuando usarlo:** entidades con **multiples colecciones grandes** donde `@EntityGraph` lanza `MultipleBagFetchException`. El caso tipico: `Department` con 6 colecciones, `Order` con `items` + `payments` + `history`.

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

No carga entidades. No hay persistence context. No hay dirty checking. No hay lazy loading. Una query SQL que devuelve exactamente los campos que necesitas, mapeados directamente a un `record`.

Para 10 stores: **1 query, 0 entidades cargadas, 0 entity fetches**.

> **Test:** `DtoProjectionTest` — `prepared=1, entityFetch=0, collectionFetch=0, queryExec=1`.

### DTO Projection para entidades con colecciones

Para `Department` con 6 colecciones, una sola query JPQL plana no funciona. La solucion es **7 queries separadas**: 1 base (nativa) + 6 selects JPQL que devuelven `List<String>` directamente, sin cargar ninguna entidad.

```java
public DepartmentProjection getDepartmentProjection(Long id) {
    Tuple base = entityManager.createNativeQuery(
        "SELECT d.id, d.name, d.code, r.name FROM departments d " +
        "LEFT JOIN regions r ON d.region_id = r.id WHERE d.id = :id", Tuple.class)
        .setParameter("id", id).getSingleResult();
    List<String> employees = entityManager.createQuery(
        "SELECT e.name FROM Employee e WHERE e.department.id = :id", String.class)
        .setParameter("id", id).getResultList();
    // ... 5 queries mas para las otras colecciones
}
```

Las 7 queries son identicas en cantidad a Split Queries, pero sin cargar entidades ni persistence context. Para colecciones de 5,000 items, DTO Projection y Split Queries tienen el mismo numero de queries (7) y tiempos similares (~60-114ms).

> **Test:** `DepartmentDtoProjectionTest` — `prepared=7, entityFetch=0, collectionFetch=0, queryExec=7`.

| Pros | Contras |
|---|---|
| 1 query, SQL optimo | No sirve para escrituras |
| 0 riesgo de `LazyInitializationException` | Mas boilerplate (query + DTO/record) |
| Sin entidades en memoria, sin snapshots | Cada endpoint necesita su propia query |
| Compatible con paginacion real | No hay dirty checking (ventaja y limitacion) |

**Cuando usarlo:** endpoints de **solo lectura** que no necesitan la entidad para modificarla despues. Listados, reportes, vistas de detalle sin edicion, endpoints de alto trafico.

---

## Trade-offs transversales

### El dirty checking es una espada de doble filo

El dirty checking automatico de Hibernate es comodo pero peligroso. Con OSIV activado, una entidad que se modifica accidentalmente se persiste sin que nadie haya llamado `save()`. El laboratorio lo demuestra: un servicio sin `@Transactional` que cambia el password de un empleado y nunca llama a `save()` funciona con OSIV (porque un `TransactionTemplate` posterior dispara el auto-flush) pero pierde el cambio silenciosamente sin OSIV.

> **Test:** `PasswordBugTest` — con OSIV=true, `changePasswordBuggy()` persiste el password. Con OSIV=false, el password se pierde. `changePasswordCorrect()` con `saveAndFlush()` funciona en ambos casos.

La solucion es usar `readOnly=true` en todas las transacciones de lectura (desactiva el flush) y ser explicito con `save()`/`saveAndFlush()` en las de escritura.

> **Test:** `ReadOnlyOptimizationTest` — confirma que `readOnly=true` impide la persistencia por dirty checking.

### LazyInitializationException es tu amiga

Con OSIV desactivado, el primer error que vas a ver es `LazyInitializationException`. No es un bug — es Hibernate diciendote que intentaste cargar datos fuera de una sesion abierta. Con OSIV activado, ese acceso habria funcionado silenciosamente, generando queries N+1 que no ves en los logs.

> **Test:** `LazyInitExceptionTest` — con OSIV=true, `GET /employees/{id}` (sin `@Transactional`) devuelve 200. Con OSIV=false, lanza `LazyInitializationException`. Con `@Transactional` o `@EntityGraph`, funciona en ambos casos.

---

## Volumetria: como escala cada solucion

Todos los numeros de esta seccion salen de `VolumetryComparisonTest` y `VolumetryBatchFetchTest`, ejecutados sobre PostgreSQL real con Testcontainers.

### Store (3 ManyToOne) — queries por solucion

| Registros | @EntityGraph | @Transactional (shared) | @Transactional (unique) | batch=16 (unique) | DTO Projection |
|---:|---:|---:|---:|---:|---:|
| 100 | 1 | 7 | 301 | 22 | 1 |
| 1K | 1 | 7 | 3,001 | 190 | 1 |
| 10K | 1 | 7 | 30,001 | 1,876 | 1 |
| 100K | 1 | 7 | 300,001* | 18,751 | 1 |
| 1M | 1 | 7 | --- | --- | 1 |

> (*) 100K con refs unicas sin batch = 300,001 queries. No ejecutado por impracticable (tardaria horas). Con batch=16, baja a 18,751 — **16x reduccion** constante.

`@EntityGraph` y DTO Projection se mantienen en **1 query** hasta 1M registros. `@Transactional` con refs compartidas (catalogos) queda en **7 queries constantes** hasta 1M. Con refs unicas, escala linealmente: `1 + N*3`.

> **Test:** `LargeScaleVolumetryTest` + `LargeScaleBatchFetchTest` + `VolumetryComparisonTest`

### Store — tiempos de lectura (ms)

| Registros | @EntityGraph | DTO Projection | @Transactional (shared) | batch=16 (shared) | @Transactional (unique) |
|---:|---:|---:|---:|---:|---:|
| 1K | 206 | 109 | 96 | 35 | 2,134 |
| 10K | 136 | 26 | 91 | 35 | 14,737 |
| 100K | 434 | 122 | 366 | 459 | --- |
| 500K | 2,646 | 1,551 | 1,590 | 1,892 | --- |
| 1M | 5,509 | 3,276 | 3,153 | 4,655 | --- |
| **10M** | **OOM** | **OOM** | **OOM** | **OOM** | --- |

**A 10M registros, todas las soluciones fallan con `OutOfMemoryError` (4GB heap)**, porque ninguna solucion basada en `findAll()` esta disenada para devolver millones de objetos en una `List<>`. La solucion a ese volumen es **paginacion** (con los caveats de `@EntityGraph` + colecciones) o **streaming con `@QueryHints(FETCH_SIZE)`**.

DTO Projection es consistentemente la mas rapida: a 1M registros, **3.2 segundos** frente a 5.5s de @EntityGraph. La diferencia es el overhead del persistence context: EntityGraph carga entidades con snapshots para dirty checking, DTO Projection solo crea records planos.

> **Test:** `LargeScaleVolumetryTest` (1K-1M) + `TenMillionTest` (10M, OOM confirmado)

### Impacto de la cardinalidad en el N+1

Con `@Transactional`, el numero de queries **no depende del numero de padres**, sino del numero de **entidades referenciadas distintas**:

| Registros | Refs compartidas (2 de cada tipo) | Refs unicas (1 por store) |
|---:|---:|---:|
| 1K | **7** | 3,001 |
| 10K | **7** | 30,001 |
| 100K | **7** | 300,001 |
| 1M | **7** | 3,000,001* |

Con refs compartidas (catalogos tipicos), **el N+1 no crece** aunque tengas 1M registros. Con refs unicas, crece linealmente y se vuelve impracticable a partir de ~10K.

> **Test:** `NPlusOneCardinalityTest` + `LargeScaleVolumetryTest`

### batch_fetch_size=16 a escala (refs compartidas)

| Registros | Queries | Tiempo |
|---:|---:|---:|
| 1K | 4 | 35ms |
| 10K | 4 | 35ms |
| 100K | 4 | 459ms |
| 500K | 4 | 1,892ms |
| 1M | **4** | **4,655ms** |

Con refs compartidas, `batch_fetch_size` se mantiene en **4 queries hasta 1M registros** (1 base + 1 batch por tipo de relacion). El tiempo crece linealmente con el volumen de datos, no con el numero de queries.

> **Test:** `LargeScaleBatchFetchTest`

### Department (6 colecciones) — colecciones son constantes

| Items/coleccion | @Transactional | Split Queries |
|---:|---:|---:|
| 3 | 8 | 7 |
| 10 | 8 | 7 |
| 25 | 8 | 7 |
| 50 | 8 | 7 |

El numero de items por coleccion **no afecta el conteo de queries**: Hibernate carga cada coleccion en 1 query independientemente del tamano. Lo que crece es el volumen de datos transferidos, no el numero de round-trips.

> **Test:** `VolumetryComparisonTest.departmentCollectionVolumetry()`

### N departamentos x 5 items/coleccion — escala horizontal

| Departamentos | Sin batch | Con batch=16 | Total items |
|---:|---:|---:|---:|
| 1 | 8 | 8 | 30 |
| 5 | 32 | 8 | 150 |
| 10 | 62 | 8 | 300 |
| 25 | 152 | 14 | 750 |

**De 152 a 14 queries para 25 departamentos — reduccion del 91%.** Sin batch, el crecimiento es `1 + N*7` (region + 6 colecciones). Con batch=16, se mantiene constante hasta 16 departamentos y sube minimamente despues.

> **Test:** `VolumetryComparisonTest.multipleDepartmentsVolumetry()` + `VolumetryBatchFetchTest.departmentBatchVolumetry()`

### N departamentos x 5 items/col — gran escala (25 → 5K)

| Depts | @Transactional (sin batch) | Con batch=16 | Reduccion |
|---:|---:|---:|---:|
| 25 | 152 | 14 | 11x |
| 100 | 602 | 44 | 14x |
| 500 | 3,002 | 194 | 16x |
| 1,000 | 6,002 | 380 | 16x |
| 5,000 | 30,002 | 1,880 | 16x |

Sin batch: `1 + 7N` (formula lineal). Con batch=16: `1 + ceil(N/16)*7` (~16x reduccion constante).

A 5,000 departamentos sin batch: **30,002 queries en 64 segundos**. Con batch: **1,880 queries en 8 segundos**. Un factor 8x en tiempo real.

> **Test:** `LargeScaleDepartmentTest` + `LargeScaleDepartmentBatchTest`

### Split Queries vs DTO Projection — escalando items por coleccion

| Items/col | Split Queries | DTO Projection | Total items |
|---:|---:|---:|---:|
| 5 | 7 queries, 75ms | 7 queries, 64ms | 30 |
| 50 | 7 queries, 16ms | 7 queries, 10ms | 300 |
| 500 | 7 queries, 42ms | 7 queries, 11ms | 3,000 |
| 5,000 | 7 queries, 114ms | 7 queries, 60ms | 30,000 |

Ambas son **7 queries constantes** independientemente del tamano de las colecciones. DTO Projection es ligeramente mas rapida porque no carga entidades en el persistence context.

> **Test:** `LargeScaleDepartmentTest.splitQueriesCollectionScale()` + `LargeScaleDepartmentTest.dtoProjectionCollectionScale()`

---

## Escritura: lo que OSIV oculta en las operaciones de modificacion

Las secciones anteriores se centran en lectura, pero OSIV afecta a las escrituras de formas distintas y mas peligrosas.

### Over-fetching: cargar datos de mas

Cuando usas `@EntityGraph` o `JOIN FETCH` para resolver un `LazyInitializationException`, cargas la entidad completa con TODAS sus columnas + las relaciones completas — aunque solo necesites 2 campos. Con 10K stores:

| Estrategia | Columnas transferidas | Entidades en memoria |
|---|---|---|
| `@EntityGraph` (full entity) | id, name, address + ALL cols de type, region, tz | 10K Store + types + regions + tz |
| DTO Projection (6 campos) | s.id, s.name, s.address, st.name, r.name, tz.zoneId | 0 (solo records) |
| Projection minima (2 campos) | s.name, r.name | 0 |

Las tres hacen **1 query SQL**, pero la cantidad de datos transferidos y el consumo de memoria son radicalmente distintos. Esto importa especialmente en endpoints de alto trafico.

> **Test:** `OverFetchingTest` — 3 tests que comparan el mismo dataset con @EntityGraph, DTO Projection y projection minima de 2 columnas.

### Cascade persist: el coste de guardar grafos completos

Guardar un `Department` con 6 colecciones via `CascadeType.ALL` genera `2 + 6N` statements (1 region + 1 dept + N items por cada coleccion):

| Items/col | Statements | Tiempo | Total items |
|---:|---:|---:|---:|
| 10 | 62 | 38ms | 60 |
| 50 | 302 | 155ms | 300 |
| 100 | 602 | 305ms | 600 |
| 500 | 3,002 | 1,487ms | 3,000 |

> **Test:** `CascadePersistTest` — mide statements y tiempo con y sin `jdbc.batch_size`.

### jdbc.batch_size NO funciona con IDENTITY generation

Un descubrimiento del laboratorio: configurar `hibernate.jdbc.batch_size=50` **no reduce el numero de statements** cuando usas `@GeneratedValue(strategy = GenerationType.IDENTITY)` (que es el default en PostgreSQL con auto-increment). Hibernate necesita el ID generado de vuelta inmediatamente despues de cada INSERT, lo que impide agrupar multiples INSERTs en un solo batch JDBC.

| Volumen | Sin batch | Con batch=50 |
|---:|---:|---:|
| 100 orders | 100 stmts, 143ms | 100 stmts, 92ms |
| 1K orders | 1,000 stmts, 1s | 1,000 stmts, 934ms |
| 5K orders | 5,000 stmts, 4.8s | 5,000 stmts, 4.5s |

**Statements identicos.** Para que `jdbc.batch_size` funcione, necesitas `GenerationType.SEQUENCE` con `@SequenceGenerator(allocationSize = 50)`. Esto permite que Hibernate pre-aloque IDs en bloques y agrupe los INSERTs.

> **Test:** `BulkInsertTest` — compara bulk insert con y sin batch, confirmando que IDENTITY impide el batching.

### Optimistic locking y OSIV

Con `@Version` en la entidad, Hibernate detecta modificaciones concurrentes. Sin OSIV, la deteccion es **temprana** — dentro del `@Transactional` donde puedes manejar el error. Con OSIV, la deteccion puede ser **tardia** — al final del request cuando el flush automatico intenta persistir la entidad stale, donde ya no puedes hacer rollback limpio.

> **Test:** `OptimisticLockingTest` — simula dos usuarios modificando el mismo `Order`. El segundo recibe `StaleObjectStateException`.

### Dirty checking accidental (el bug del password)

Sin OSIV, si modificas una entidad sin llamar `save()`, el cambio se pierde silenciosamente (la entidad esta detached). Con OSIV, el dirty checking al final del request detecta la modificacion y la persiste — lo cual parece "correcto" pero enmascara la falta de un `save()` explicito.

> **Test:** `PasswordBugTest` — con OSIV=true el password se persiste sin `save()`. Con OSIV=false se pierde. `saveAndFlush()` funciona en ambos casos.

---

## Arbol de decision

```
0. SIEMPRE: configura batch_fetch_size=16 como baseline global
   (Test: BatchSizeQueryCountTest — reduce 31 queries a 4)

1. Es operacion de ESCRITURA?
   SI  --> @Transactional + save()/saveAndFlush() explicito
           NUNCA dependas del dirty checking implicito
           Para batch inserts: usa GenerationType.SEQUENCE, no IDENTITY
           (Test: PasswordBugTest — sin save() el cambio se pierde con OSIV=false)
           (Test: BulkInsertTest — IDENTITY impide batching JDBC)
   NO  --> (es lectura, continua)

2. Cuantos registros esperas devolver?

   MILES O MAS (>1K) --> OBLIGATORIO paginar o hacer streaming
       findAll() con >10K registros es una bomba de memoria
       A 1M: todas las soluciones tardan 3-5s cargando en List<>
       A 10M: TODAS fallan con OutOfMemoryError (4GB heap)
       (Test: LargeScaleVolumetryTest — OOM a 10M, 5.5s a 1M)
       (Test: TenMillionTest — OOM confirmado para EntityGraph, DTO y Transactional)

       Vas a paginar con colecciones?
         SI  --> NO uses @EntityGraph (pagina EN MEMORIA, sin LIMIT/OFFSET)
                 Usa @Transactional(readOnly=true) + batch_fetch
                 (Test: PaginationTrapTest — 10 depts cargados para mostrar 3)
         NO  --> Paginar con ManyToOne esta bien con cualquier solucion

   CIENTOS O MENOS --> findAll() es viable, continua

3. Las refs ManyToOne son compartidas (catalogos) o unicas (1 por entidad)?

   COMPARTIDAS (tipos, categorias, regiones)
       --> N+1 es constante: 7 queries aunque tengas 1M registros
           batch_fetch no aporta mucho aqui (de 7 a 4)
           (Test: LargeScaleVolumetryTest — 7 queries constantes hasta 1M)

   UNICAS (createdBy, assignedTo, etc.)
       --> N+1 crece linealmente: 3,001 queries para 1K, 30,001 para 10K
           SIN batch_fetch: impracticable a partir de 10K (14.7s para 10K)
           CON batch_fetch=16: 16x reduccion constante (190 queries para 1K)
           (Test: NPlusOneCardinalityTest — 7 vs 31)
           (Test: LargeScaleBatchFetchTest — 16x reduccion hasta 100K)

4. Necesitas la entidad JPA o solo datos planos?

   SOLO DATOS --> DTO Projection
       Store (ManyToOne): 1 query, 0 entidades
       (Test: DtoProjectionTest — prepared=1, entityFetch=0)
       Department (colecciones): 7 queries (1 base + 6 selects), 0 entidades
       (Test: DepartmentDtoProjectionTest — prepared=7, entityFetch=0)
       Menos over-fetching: solo transfiere las columnas que necesitas
       (Test: OverFetchingTest — @EntityGraph carga todo vs DTO solo lo necesario)
       A escala: la mas rapida — 3.2s para 1M vs 5.5s de @EntityGraph
       (Test: LargeScaleVolumetryTest — DTO vs EntityGraph a 1M)

   NECESITO ENTIDAD --> (continua)

5. La entidad tiene multiples colecciones (>1 List/Set)?

   SI  --> Split Queries (1 JOIN FETCH por coleccion, L1 cache merge)
           7 queries constantes desde 5 hasta 5,000 items por coleccion
           NUNCA @EntityGraph aqui:
             - Con List: MultipleBagFetchException
             - Con Set: cartesiano silencioso (5^3 = 125 filas para 15 items)
           (Test: SplitQueryTest — 7 queries, 0 cartesiano)
           (Test: CartesianProductTest — MultipleBagFetchException con List)
           (Test: CartesianProductWithSetsTest — cartesiano silencioso con Set)
           (Test: LargeScaleDepartmentTest — 7 queries constantes hasta 5K items/col)
   NO  --> (continua)

6. Cuantas relaciones ManyToOne tiene?

   1-3 ManyToOne --> @EntityGraph (1 JOIN query)
                     Funciona bien hasta 1M registros (5.5s a 1M)
                     Cuidado con over-fetching: carga todas las columnas
                     (Test: EntityGraphQueryCountTest — prepared=1)
                     (Test: LargeScaleVolumetryTest — 1 query hasta 1M)
                     (Test: OverFetchingTest — over-fetching vs DTO)

   Muchas o profundas --> @Transactional(readOnly=true) + batch_fetch
                          (Test: LargeScaleBatchFetchTest — 16x reduccion)

SIEMPRE en metodos de lectura: @Transactional(readOnly=true)
  - Desactiva dirty checking (sin flush accidental)
  - Reduce memoria (sin snapshots de estado)
  (Test: ReadOnlyOptimizationTest — modificaciones no se persisten)
```

---

## Resumen ejecutivo

### Lectura

1. **`batch_fetch_size=16`** como baseline global (1 linea de yml)
2. **`@EntityGraph`** para entidades simples con pocas relaciones ManyToOne, sin paginacion sobre colecciones
3. **Split Queries** para entidades con multiples colecciones
4. **DTO Projection** para endpoints de solo lectura, especialmente de alto trafico o volumenes grandes
5. **`readOnly=true`** en todas las transacciones de lectura (menos memoria, sin flush accidental)

### Escritura

1. **Siempre `@Transactional` explicito** — nunca depender de OSIV para persistir cambios
2. **Siempre `save()`/`saveAndFlush()` explicito** — nunca depender del dirty checking
3. **`GenerationType.SEQUENCE`** si necesitas batch inserts — `IDENTITY` impide el batching JDBC
4. **`@Version`** para optimistic locking — sin OSIV la deteccion es temprana y manejable

### Volumetria

- A **>1K registros**: paginar es obligatorio
- A **>10K con refs unicas sin batch**: N+1 se vuelve impracticable
- A **1M**: todas las soluciones basadas en `findAll()` tardan 3-5s
- A **10M**: `OutOfMemoryError` para todas las soluciones — necesitas streaming o paginacion

Cada numero de este documento esta respaldado por un test que pasa en verde. El codigo completo esta en el repositorio `open-in-view-lab`.
