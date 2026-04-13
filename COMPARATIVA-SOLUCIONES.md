# Comparativa de soluciones al desactivar Open-in-View

Cuando desactivas `spring.jpa.open-in-view=false`, cada relacion lazy que se acceda fuera de una transaccion explota con `LazyInitializationException`. La pregunta ya no es *si* desactivarlo, sino *como* cargar los datos que necesitas sin depender de la sesion abierta del filtro OSIV.

No hay una unica solucion correcta. Cada estrategia tiene un rango de aplicacion distinto, y la mejor eleccion depende de la forma de las entidades que manejas: cuantas relaciones tienen, si son `ManyToOne` o colecciones, y si necesitas la entidad para escribir o solo para leer.

A continuacion presento cinco estrategias, medidas con Hibernate Statistics sobre PostgreSQL real (Testcontainers), con los numeros exactos que arrojo el laboratorio. Todos los tests del proyecto pasan en verde y son el respaldo de cada numero que aparece aqui.

---

## Tabla resumen

| Solucion | Queries (10 Store, 3 ManyToOne) | Queries (1 Dept, 6 colecciones x5) | Cartesiano? | Memoria (100K) | Esfuerzo |
|---|:---:|:---:|:---:|:---:|---|
| `@EntityGraph` | **1** | `MultipleBagFetchException` | Si | 117 MB | Anotacion en repository |
| `@Transactional(readOnly=true)` | 7 o 31 (*) | 8 | No | ~117 MB | Anotacion en servicio |
| `batch_fetch_size=16` | **4** | 8 | No | ~117 MB | 1 linea en `application.yml` |
| `Hibernate.initialize()` | 7 | 8 | No | ~117 MB | Codigo manual explicito |
| Split Queries | n/a | **7** | No | ~117 MB | Codigo manual |
| DTO Projection (JPQL) | **1** | **7** | No | 56 MB | Query JPQL + record |
| Interface Projection | **1** | n/a | No | 130 MB | Interface + @Query |
| JdbcClient | **1** | **7** | No | 56 MB | SQL manual |
| StatelessSession | n/a (escritura) | n/a | No | ~0 | API Hibernate directa |

> (*) 7 queries cuando las 10 stores comparten 2 references de cada tipo. **31 queries** cuando cada store tiene referencias unicas. El N+1 depende de la cardinalidad de las entidades referenciadas, no del numero de entidades padre.
>
> La columna "Memoria (100K)" viene de `MemoryFootprintTest` + `LargeScaleAllSolutionsTest`: @EntityGraph carga **118 MB** de entidades + snapshots, DTO solo **59 MB** de records planos. Interface Projection sorprende con **130 MB** (proxies dinamicos mas pesados que entidades managed).

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

### El caso feliz: ManyToOne + 1 coleccion

No todo son problemas con `@EntityGraph`. Con 3 `ManyToOne` + 1 sola coleccion (`storeEmployees`), funciona perfectamente: **1 query, sin cartesiano, sin excepcion**.

> **Test:** `EntityGraphHappyPathTest` — `findAllWithRelationsAndEmployeesBy()` devuelve 5 stores con todas sus relaciones en 1 query.

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

## 3b. `@BatchSize` per-collection

```java
@OneToMany
@JoinColumn(name = "department_id")
@BatchSize(size = 16)
private List<Employee> employees = new ArrayList<>();
```

Mismo efecto que `default_batch_fetch_size=16` pero con granularidad por coleccion. Puedes poner `@BatchSize(size = 50)` en una coleccion grande y `@BatchSize(size = 8)` en una pequena.

Para 25 departamentos con 6 colecciones x 5 items: **13 queries** (vs 176 sin nada, vs 14 con global batch_fetch).

> **Test:** `BatchSizePerCollectionTest` — 13 queries, mismo efecto que global batch_fetch.

**Nota Hibernate 7:** `@BatchSize` solo funciona en colecciones `@OneToMany`. En `@ManyToOne` ya no esta soportado — usa `default_batch_fetch_size` global para ManyToOne.

| Pros | Contras |
|---|---|
| Control granular por coleccion | Anotacion en cada coleccion (mas verboso) |
| Permite tamaños de batch distintos | No funciona en @ManyToOne (Hibernate 7) |

**Cuando usarlo:** cuando necesitas batch sizes distintos por coleccion. Si un tamaño unico vale para todo, usa `default_batch_fetch_size` global.

---

## 3c. `@Fetch(FetchMode.SUBSELECT)`

```java
@OneToMany
@JoinColumn(name = "department_id")
@Fetch(FetchMode.SUBSELECT)
private List<Employee> employees = new ArrayList<>();
```

SUBSELECT carga TODAS las colecciones de un tipo en **1 sola query** usando una subquery que repite la query original:

```sql
SELECT * FROM employees WHERE department_id IN (SELECT id FROM departments)
```

A diferencia de `batch_fetch_size` que agrupa en chunks de 16, SUBSELECT siempre hace 1 query por tipo de coleccion **independientemente del numero de padres**.

| Depts | SUBSELECT | batch_fetch=16 | Sin nada |
|---:|---:|---:|---:|
| 5 | 7 | 8 | 36 |
| 25 | **7** | 14 | 176 |
| 100 | **7** | 44 | 701 |

Con 25 departamentos: SUBSELECT hace **7 queries** vs 14 de batch_fetch — la mitad. Con 100 departamentos: **7 vs 44** — 6x menos. SUBSELECT no crece con N.

> **Test:** `SubselectFetchTest` — 7 queries constantes para 5, 25 y 100 departamentos. Comparativa con batch_fetch y sin nada.

| Pros | Contras |
|---|---|
| Siempre 1 query por tipo de coleccion | Repite la query original como subquery (puede ser costoso si la query original es compleja) |
| No crece con N (constante) | No configurable globalmente (es por coleccion) |
| Mejor que batch para N grande | Carga TODAS las colecciones, no solo un batch |

**Cuando usarlo:** entidades con colecciones `@OneToMany` donde cargas muchos padres a la vez (findAll, listados). Es la mejor opcion cuando `N > 16` y batch_fetch empieza a hacer multiples rounds. **No usar** si la query original es costosa (la subquery la repite entera).

---

## 3d. `@Immutable`

```java
@Entity
@Immutable
public class DepartmentReadOnly { ... }
```

Marca una entidad como solo lectura a nivel de Hibernate. **Cero dirty checking**: no se crean snapshots, no se detectan cambios, no se hace flush. Las modificaciones se ignoran silenciosamente.

El laboratorio lo confirma: modificar el nombre de un `@Immutable` y no llamar `save()` no persiste nada. Con una entidad normal, el dirty checking lo detecta y persiste al commit.

> **Test:** `ImmutableEntityTest` — `@Immutable` ignora modificaciones vs entidad normal que las persiste via dirty checking.

| Pros | Contras |
|---|---|
| Cero overhead de dirty checking | No puedes escribir con esta entidad |
| Menos memoria (sin snapshots) | Modificaciones ignoradas silenciosamente (peligroso si no sabes que es immutable) |

**Cuando usarlo:** entidades de **solo lectura** como vistas, tablas de catalogo, datos historicos. Util para endpoints de lectura donde sabes que nunca vas a modificar la entidad.

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

// En el repository:
@Query("SELECT s.id AS id, s.name AS name, s.address AS address, " +
       "st.name AS storeTypeName, r.name AS regionName, tz.zoneId AS timezoneZoneId " +
       "FROM Store s LEFT JOIN s.storeType st LEFT JOIN s.region r LEFT JOIN s.timezone tz")
List<StoreView> findAllProjectedBy();
```

Misma idea que DTO Projection pero con menos boilerplate: declaras una interfaz con getters y Spring Data genera la implementacion. **1 query, 0 entidades**, pero **130 MB** para 100K registros — sorprendentemente mas pesado que @EntityGraph (118 MB). Los proxies dinamicos de Spring Data (`java.lang.reflect.Proxy`) mantienen un `Map<String, Object>` por instancia, lo que es mas costoso en memoria que un `record` plano (59 MB con DTO Projection).

> **Test:** `InterfaceProjectionTest` — `prepared=1, entityFetch=0, queryExec=1`.

| Pros | Contras |
|---|---|
| Menos boilerplate que constructor DTO | Requiere `@Query` con aliases |
| Spring Data genera la implementacion | No funciona para colecciones (solo plano) |
| Misma eficiencia que DTO Projection | Menos control que un record explicito |

**Cuando usarlo:** cuando DTO Projection es la solucion correcta pero quieres menos codigo. Ideal para endpoints de lectura simples.

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

Control explicito sobre que relaciones se cargan y cuando. Genera las mismas queries que `@Transactional` (N+1), pero el codigo deja claro que la carga es intencional. No hay "magia" — si una relacion se carga, es porque tu la pediste.

Para 10 stores: **7 queries** (vs 1 de @EntityGraph).

> **Test:** `HibernateInitializeTest` — `Hibernate.initialize()` 7 queries vs `@EntityGraph` 1 query.

| Pros | Contras |
|---|---|
| Control explicito de que se carga | Mismo N+1 que @Transactional |
| Codigo autodocumentado | Mas verboso |
| Funciona con cualquier relacion | No reduce queries |

**Cuando usarlo:** cuando necesitas que el codigo sea **explicito** sobre que relaciones se cargan. Util en servicios complejos donde `@EntityGraph` no es suficiente y quieres que otro desarrollador entienda que datos se estan cargando.

---

## 8. Memoria: el coste oculto del persistence context

Con 100K stores, el test `MemoryFootprintTest` y `LargeScaleAllSolutionsTest` midieron:

| Solucion | Memoria | Por que |
|---|---:|---|
| `@EntityGraph` | **118 MB** | Entidades managed + snapshots para dirty checking |
| `@Transactional` | **108 MB** | Entidades managed + snapshots (similar a EntityGraph) |
| `initialize()` | **111 MB** | Entidades managed + snapshots (similar a EntityGraph) |
| Interface Projection | **130 MB** | Proxies dinamicos de Spring Data son pesados |
| DTO Projection | **59 MB** | Solo records planos, sin persistence context |
| JdbcClient | **56 MB** | Solo records planos, sin Hibernate |

**Sorpresa: Interface Projection usa MAS memoria que @EntityGraph** (130 MB vs 118 MB). Aunque Interface Projection no carga entidades en el persistence context, Spring Data genera proxies dinamicos para cada fila devuelta. Estos proxies (`java.lang.reflect.Proxy`) mantienen un `Map<String, Object>` interno por cada instancia, lo que resulta mas pesado que un `record` plano. A 100K registros, la diferencia es significativa.

JdbcClient y DTO Projection son las opciones mas eficientes en memoria: **56-59 MB** frente a 108-130 MB de las alternativas basadas en entidades o proxies. La diferencia es 2x o mas.

A 100K registros, la diferencia entre EntityGraph y DTO es ~60 MB. A 1M seria ~600 MB de overhead solo por usar entidades en vez de DTOs. Este es el motivo por el que `findAll()` con entidades explota con OOM mucho antes que con DTO Projection.

> **Test:** `MemoryFootprintTest` + `LargeScaleAllSolutionsTest` — mide heap antes/despues de cargar 100K stores con cada solucion.

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

### Comparativa definitiva: todas las soluciones head-to-head

Los tests `LargeScaleAllSolutionsTest` y `LargeScaleSubselectVsBatchTest` enfrentan todas las soluciones bajo las mismas condiciones, con 100K stores y hasta 5K departamentos.

#### Store (3 ManyToOne, refs compartidas) — 100K registros

| Solucion | Queries | Tiempo | Memoria |
|---|---:|---:|---:|
| JdbcClient | 1 | 137ms | 56 MB |
| DTO Projection | 1 | 127ms | 59 MB |
| Interface Projection | 1 | 277ms | 130 MB |
| @Transactional | 7 | 242ms | 108 MB |
| initialize() | 7 | 273ms | 111 MB |
| @EntityGraph | 1 | 369ms | 118 MB |

JdbcClient y DTO Projection son practicamente identicos en rendimiento y memoria. Interface Projection sorprende negativamente: aunque hace 1 sola query, consume **130 MB** — mas que @EntityGraph (118 MB) — porque los proxies dinamicos de Spring Data (`java.lang.reflect.Proxy` con `Map<String, Object>`) son mas pesados que los records planos.

> **Test:** `LargeScaleAllSolutionsTest`

#### Department (6 colecciones x 5 items) — escalando departamentos

| Depts | @Transactional | SUBSELECT | Split (1 dept) | DTO Proj (1 dept) |
|---:|---:|---:|---:|---:|
| 25 | 152q 139ms | 7q 27ms | 7q 27ms | 7q 29ms |
| 100 | 602q 351ms | 7q 23ms | 7q 11ms | 7q 8ms |
| 500 | 3,002q 1,917ms | 7q 66ms | 7q 10ms | 7q 5ms |
| 1,000 | 6,002q 4,391ms | 7q 92ms | 7q 11ms | 7q 7ms |

SUBSELECT mantiene **7 queries constantes** independientemente del numero de departamentos. A 1,000 departamentos: 7 queries en 92ms vs 6,002 queries en 4.4 segundos — **48x mas rapido**.

> **Test:** `LargeScaleAllSolutionsTest`

#### SUBSELECT vs @BatchSize vs ninguno — la comparativa definitiva de colecciones

| Depts | Sin batch (N+1) | @BatchSize(16) | SUBSELECT |
|---:|---:|---:|---:|
| 25 | 152q 96ms | 13q 26ms | 7q 11ms |
| 100 | 602q 336ms | 43q 49ms | 7q 15ms |
| 500 | 3,002q 2,121ms | 193q 210ms | 7q 53ms |
| 1,000 | 6,002q 4,214ms | 379q 436ms | 7q 84ms |
| 5,000 | 30,002q 45,603ms | 1,879q 3,696ms | 7q 439ms |

A 5,000 departamentos: **SUBSELECT hace 7 queries en 439ms** vs 30,002 queries en 45.6 segundos (sin batch) — **100x mas rapido**. Incluso vs @BatchSize(16), SUBSELECT es 8x mas rapido (439ms vs 3.7s) con 270x menos queries (7 vs 1,879).

SUBSELECT es el **ganador claro** para colecciones a escala. No crece con N, y la diferencia se amplifica dramaticamente a medida que aumenta el numero de padres.

> **Test:** `LargeScaleSubselectVsBatchTest`

#### Memoria a 100K stores — ranking completo

| Solucion | Memoria |
|---|---:|
| JdbcClient | 56 MB |
| DTO Projection | 59 MB |
| @Transactional | 108 MB |
| initialize() | 111 MB |
| @EntityGraph | 118 MB |
| Interface Projection | 130 MB |

Interface Projection es la **mas pesada en memoria** de todas las soluciones medidas. Los proxies dinamicos de Spring Data son mas costosos que las entidades managed de Hibernate. Si el rendimiento en memoria importa, usa DTO Projection (record) en vez de Interface Projection (proxy).

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

Guardar un `Department` con 6 colecciones via `CascadeType.ALL` genera `2 + 6N` statements (1 region + 1 dept + N items por cada coleccion). Mediana de 5 ejecuciones tras 2 warmup:

| Items/col | Total items | Sin batch (mediana) | Con batch=50 (mediana) |
|---:|---:|---:|---:|
| 10 | 60 | 47ms | 71ms |
| 50 | 300 | 175ms | 200ms |
| 100 | 600 | 333ms | 366ms |
| 500 | 3,000 | 1,579ms | 1,620ms |

Con batch=50 es **mas lento** que sin batch. Con `IDENTITY`, la configuracion de batch_size añade overhead de coordinacion sin beneficio real porque cada INSERT necesita su propio round-trip para obtener el ID generado.

> **Test:** `BenchmarkWriteTest` — cascade persist con y sin batch, warmup=2, runs=5.

### jdbc.batch_size NO funciona con IDENTITY generation

Un descubrimiento del laboratorio: configurar `hibernate.jdbc.batch_size=50` **no reduce el numero de statements** cuando usas `@GeneratedValue(strategy = GenerationType.IDENTITY)` (que es el default en PostgreSQL con auto-increment). Hibernate necesita el ID generado de vuelta inmediatamente despues de cada INSERT, lo que impide agrupar multiples INSERTs en un solo batch JDBC. Mediana de 5 runs tras 2 warmup:

| Volumen | Sin batch (mediana) | Con batch=50 (mediana) |
|---:|---:|---:|
| 100 orders | 111ms | 113ms |
| 1K orders | 953ms | 975ms |
| 5K orders | 4,738ms | 4,718ms |

**Tiempos practicamente identicos.** Para que `jdbc.batch_size` funcione, necesitas `GenerationType.SEQUENCE` con `@SequenceGenerator(allocationSize = 50)`. Esto permite que Hibernate pre-aloque IDs en bloques y agrupe los INSERTs.

> **Test:** `BenchmarkWriteTest` — bulk insert con y sin batch, warmup=2, runs=5.

### StatelessSession: escritura sin persistence context

Para bulk inserts donde no necesitas dirty checking ni cascade, `StatelessSession` elimina el overhead del persistence context por completo:

```java
try (StatelessSession session = sessionFactory.openStatelessSession()) {
    session.beginTransaction();
    for (int i = 0; i < count; i++) {
        session.insert(order);
    }
    session.getTransaction().commit();
}
```

Mediana de 5 runs tras 2 warmup:

| Volumen | StatelessSession (mediana) | Session (mediana) |
|---:|---:|---:|
| 100 | 132ms | 115ms |
| 1K | 982ms | 1,007ms |
| 5K | 5,076ms | 4,880ms |

Con `GenerationType.IDENTITY`, la diferencia es **practicamente nula**. El bottleneck no es el persistence context sino el round-trip por cada INSERT (1 por cada ID generado). StatelessSession brilla con `GenerationType.SEQUENCE` donde puede pre-alocar IDs y agrupar inserts.

> **Test:** `BenchmarkWriteTest` — StatelessSession vs Session, warmup=2, runs=5.

### Optimistic locking y OSIV

Con `@Version` en la entidad, Hibernate detecta modificaciones concurrentes. Sin OSIV, la deteccion es **temprana** — dentro del `@Transactional` donde puedes manejar el error. Con OSIV, la deteccion puede ser **tardia** — al final del request cuando el flush automatico intenta persistir la entidad stale, donde ya no puedes hacer rollback limpio.

> **Test:** `OptimisticLockingTest` — simula dos usuarios modificando el mismo `Order`. El segundo recibe `StaleObjectStateException`.

### Dirty checking accidental (el bug del password)

Sin OSIV, si modificas una entidad sin llamar `save()`, el cambio se pierde silenciosamente (la entidad esta detached). Con OSIV, el dirty checking al final del request detecta la modificacion y la persiste — lo cual parece "correcto" pero enmascara la falta de un `save()` explicito.

> **Test:** `PasswordBugTest` — con OSIV=true el password se persiste sin `save()`. Con OSIV=false se pierde. `saveAndFlush()` funciona en ambos casos.

---

## JPA/Hibernate vs alternativas: es un problema de la herramienta?

El problema de OSIV **solo existe porque Hibernate gestiona la sesion y el lazy loading por ti**. Si usas otra herramienta para acceder a la BD, los problemas desaparecen — pero tambien las comodidades.

### Mapa de herramientas y problemas

| Herramienta | Lazy loading? | OSIV aplica? | N+1 posible? | Dirty checking? |
|---|:---:|:---:|:---:|:---:|
| **JPA/Hibernate** | Si (proxies) | Si | Si | Si |
| **Spring JdbcClient** | No | No | No | No |
| **jOOQ** | No | No | No | No |
| **MyBatis** | Configurable | No | Configurable | No |
| **Spring Data R2DBC** | No | No | No | No |
| **Native queries via JPA** | No (resultado plano) | La sesion sigue abierta | No | Si (si tocas entidades) |

Con JdbcClient (o JdbcTemplate, jOOQ, etc.) escribes el SQL tu mismo: no hay proxies, no hay sesion persistente, no hay lazy loading, no hay dirty checking accidental. Controlas exactamente que datos se cargan y cuando.

### JPA DTO Projection vs JdbcClient — misma query, mismo resultado

Ambos ejecutan la misma query SQL y devuelven el mismo `StoreProjection`. La diferencia es el overhead:

| Registros | JPA DTO Projection | JdbcClient | Diferencia |
|---:|---:|---:|---|
| 1K | 96ms | 9ms | JDBC 10x mas rapido |
| 10K | 22ms | 21ms | Similar |
| 100K | 159ms | 93ms | JDBC 1.7x mas rapido |
| 500K | 1,437ms | 1,430ms | Similar |

A pocos registros (cold start), JPA tiene overhead de inicializacion del query parser. A gran volumen, la diferencia se diluye porque el bottleneck es la transferencia de datos, no el framework.

> **Test:** `JpaVsJdbcTest.jpaVsJdbcProjection()` — misma query, mismos datos, dos caminos distintos.

### @EntityGraph (full entity) vs JdbcClient (minimal DTO) — overhead real

Aqui es donde la diferencia se nota: @EntityGraph carga entidades completas con persistence context y snapshots para dirty checking. JdbcClient devuelve DTOs planos sin overhead.

| Registros | @EntityGraph | JdbcClient | Ratio |
|---:|---:|---:|---|
| 1K | 111ms | 6ms | **18.5x** mas lento |
| 10K | 93ms | 34ms | **2.7x** mas lento |
| 100K | 542ms | 275ms | **2.0x** mas lento |

A 1K registros, el overhead del persistence context (crear entidades managed, snapshots para dirty checking, L1 cache) es **18x** mas caro que mapear rows a records planos. A 100K la diferencia se reduce a 2x porque la transferencia de datos domina.

> **Test:** `JpaVsJdbcTest.entityGraphVsJdbc()` — mismos datos, @EntityGraph vs JdbcClient.

### Que se pierde al salir de JPA

| Lo que pierdes | Impacto |
|---|---|
| Dirty checking automatico | Tienes que hacer UPDATE explicito para cada cambio |
| Cascade persist/merge/remove | Tienes que gestionar las relaciones manualmente |
| L1 cache (persistence context) | Consultas repetidas al mismo ID van a BD |
| `@EntityGraph` declarativo | Escribes los JOINs a mano |
| Herencia de entidades | Mappings manuales por tipo |
| Optimistic locking (`@Version`) | Lo implementas tu con WHERE version = ? |
| Spring Data repositories | Escribes los queries tu mismo |

### Cuando vale la pena salir de JPA

- **Endpoints de solo lectura con alto trafico**: JdbcClient + DTO es mas rapido y mas simple
- **Reportes y agregaciones**: SQL nativo es mas expresivo que JPQL para GROUP BY, window functions, CTEs
- **Bulk operations**: INSERT/UPDATE masivo es ordenes de magnitud mas rapido sin persistence context
- **Microservicios sin estado**: si no necesitas dirty checking ni cascade, JPA es overhead innecesario

### Cuando JPA sigue siendo la mejor opcion

- **CRUD con relaciones complejas**: cascade y dirty checking ahorran mucho boilerplate
- **Dominios con logica de negocio en entidades**: DDD con rich domain models
- **Equipos grandes**: las convenciones de JPA/Spring Data reducen la variabilidad del codigo

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

   SOLO DATOS --> DTO Projection o Interface Projection
       Store (ManyToOne): 1 query, 0 entidades, 56 MB para 100K
       (Test: DtoProjectionTest — prepared=1, entityFetch=0)
       (Test: InterfaceProjectionTest — misma eficiencia, menos boilerplate)
       Department (colecciones): 7 queries (1 base + 6 selects), 0 entidades
       (Test: DepartmentDtoProjectionTest — prepared=7, entityFetch=0)
       Menos over-fetching: solo transfiere las columnas que necesitas
       (Test: OverFetchingTest — @EntityGraph 118 MB vs DTO 59 MB para 100K)
       Alternativa sin JPA: JdbcClient (2-18x mas rapido que @EntityGraph)
       (Test: JpaVsJdbcTest — misma query, menos overhead)

   NECESITO ENTIDAD --> Es de solo lectura?
       SI  --> Considera @Immutable (sin dirty checking, sin snapshots)
               (Test: ImmutableEntityTest — modificaciones ignoradas)
       NO  --> (continua)

5. La entidad tiene multiples colecciones (>1 List/Set)?

   SI  --> Cuantos padres cargas a la vez?

       1 SOLO PADRE (findById) --> Split Queries
           7 queries constantes desde 5 hasta 5,000 items por coleccion
           (Test: SplitQueryTest — 7 queries, 0 cartesiano)
           (Test: LargeScaleDepartmentTest — 7 queries constantes hasta 5K items/col)

       MUCHOS PADRES (findAll) --> @Fetch(FetchMode.SUBSELECT)
           Siempre 7 queries (1 base + 6 subselects) SIN IMPORTAR N
           Mejor que batch_fetch cuando N > 16
           (Test: SubselectFetchTest — 7 queries para 5, 25 y 100 departamentos)
           Alternativa: @BatchSize(16) per-collection (mismo efecto que global)
           (Test: BatchSizePerCollectionTest — 13 queries para 25 depts)

       NUNCA @EntityGraph con multiples colecciones:
         - Con List: MultipleBagFetchException
         - Con Set: cartesiano silencioso (5^3 = 125 filas para 15 items)
         (Test: CartesianProductTest + CartesianProductWithSetsTest)

   NO  --> (continua)

6. Cuantas relaciones ManyToOne tiene?

   1-3 ManyToOne --> @EntityGraph (1 JOIN query)
                     Funciona bien hasta 1M registros (5.5s a 1M)
                     Cuidado con over-fetching: carga todas las columnas (118 MB vs 59 MB)
                     (Test: EntityGraphQueryCountTest — prepared=1)
                     (Test: MemoryFootprintTest + LargeScaleAllSolutionsTest — 118 MB vs 59 MB para 100K)

   Muchas o profundas --> @Transactional(readOnly=true) + batch_fetch
                          (Test: LargeScaleBatchFetchTest — 16x reduccion)

SIEMPRE en metodos de lectura: @Transactional(readOnly=true)
  - Desactiva dirty checking (sin flush accidental)
  - Reduce memoria (sin snapshots de estado)
  (Test: ReadOnlyOptimizationTest — modificaciones no se persisten)
```

---

## Resumen ejecutivo

### Lectura — la combinacion ganadora

1. **`batch_fetch_size=16`** como baseline global (1 linea de yml — mejora todo)
2. **`@Fetch(FetchMode.SUBSELECT)`** para colecciones cuando cargas muchos padres (7 queries constantes sin importar N)
3. **Split Queries** para 1 entidad con multiples colecciones
4. **`@EntityGraph`** para entidades simples con 1-3 ManyToOne (1 query, pero 118 MB vs 59 MB a 100K)
5. **DTO Projection** para solo lectura (59 MB, la mas rapida a escala). **Interface Projection** es comoda pero usa 130 MB (proxies dinamicos)
6. **JdbcClient** cuando no necesitas JPA (2-18x mas rapido que @EntityGraph)
7. **`@Immutable`** para entidades de solo lectura (sin dirty checking, sin snapshots)
8. **`readOnly=true`** siempre en transacciones de lectura (menos memoria, sin flush accidental)

### Escritura

1. **Siempre `@Transactional` explicito** — nunca depender de OSIV para persistir cambios
2. **Siempre `save()`/`saveAndFlush()` explicito** — nunca depender del dirty checking
3. **`GenerationType.SEQUENCE`** si necesitas batch inserts — `IDENTITY` impide el batching JDBC
4. **`StatelessSession`** para bulk writes sin persistence context
5. **`@Version`** para optimistic locking — sin OSIV la deteccion es temprana y manejable

### Volumetria

- A **>1K registros**: paginar es obligatorio
- A **>10K con refs unicas sin batch**: N+1 se vuelve impracticable
- A **1M**: todas las soluciones basadas en `findAll()` tardan 3-5s. @EntityGraph 118 MB vs DTO 59 MB
- A **10M**: `OutOfMemoryError` para todas las soluciones — necesitas streaming o paginacion
- **`@Fetch(SUBSELECT)`**: 7 queries constantes desde 5 hasta 5,000 departamentos (100x mas rapido que N+1 a 5K depts)
- **Interface Projection**: 130 MB a 100K — mas pesada que @EntityGraph (118 MB) por proxies dinamicos
- **SUBSELECT vs @BatchSize(16) a 5K depts**: 7q/439ms vs 1,879q/3.7s — SUBSELECT gana por 8x

### Cuando NO necesitas JPA

- Lectura pura con alto trafico → **JdbcClient** (2-18x mas rapido)
- Reportes y agregaciones → SQL nativo
- Bulk writes → **StatelessSession** o SQL nativo con `generate_series`

Cada numero de este documento esta respaldado por un test que pasa en verde. Los benchmarks usan 2 warmup runs + 5 ejecuciones medidas con mediana. El codigo completo esta en el repositorio `open-in-view-lab`.

---

## Limitaciones de este benchmark

Los numeros de este laboratorio son utiles para comparar soluciones entre si, pero no son extrapolables directamente a produccion. Las siguientes limitaciones sesgan los resultados:

| Limitacion | Impacto en los resultados |
|---|---|
| **Single-thread** | Todos los tests corren en 1 hilo. En produccion hay N threads concurrentes peleando por conexiones del pool. Los tiempos reales seran peores y las soluciones que retienen conexiones mas tiempo (@Transactional, @EntityGraph) penalizan mas |
| **Sin indices custom** | Las tablas solo tienen PKs e indices de FK automaticos. En produccion, indices bien diseñados pueden reducir drasticamente los tiempos de JOIN y WHERE. Los resultados de este lab son worst-case |
| **Datos sinteticos uniformes** | `generate_series` produce datos perfectamente distribuidos. En produccion hay skew (unos departamentos con 5 empleados, otros con 5,000). Esto afecta especialmente a batch_fetch y SUBSELECT |
| **Solo PostgreSQL 16** | Los resultados cambian con otros motores. MySQL no soporta SUBSELECT igual. Oracle tiene optimizaciones distintas para batch fetching. Los numeros de queries son universales, pero los tiempos son especificos de PostgreSQL |
| **Testcontainers (Docker)** | La BD corre en un container Docker local, no en un servidor dedicado. Latencia de red = 0. En produccion, la latencia de red amplifica la diferencia entre 1 query y 30,000 queries |
| **Sin cache L2** | No hay cache de segundo nivel configurada (Ehcache, Caffeine). Con L2 cache, batch_fetch y @Transactional pueden ser mucho mas rapidos porque las entidades referenciadas ya estan en cache. @EntityGraph y DTO Projection bypasean la L2 cache |
| **JVM cold start en algunos tests** | Aunque los benchmarks usan warmup, los tests de volumetria no lo hacen — sus tiempos incluyen JIT compilation. Los benchmarks (BenchmarkStoreTest, BenchmarkDepartmentTest) son mas fiables |
| **Heap fijo (2GB)** | Los tests corren con -Xmx2g. Con mas heap, los tests de 10M podrian no dar OOM. Con menos, el umbral baja |

**Lo que SI es fiable en estos resultados:**
- El **conteo de queries** es exacto e independiente del entorno
- Las **formulas de escalado** (1+7N, 1+ceil(N/16)*7, constante 7) son universales
- La **proporcion de memoria** entre soluciones (2x entre entities y DTOs) se mantiene
- Los **fallos** (MultipleBagFetchException, OOM, paginacion en memoria) son reproducibles en cualquier entorno
