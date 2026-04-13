package com.example.osivlab.solutions;

import com.example.osivlab.AbstractIntegrationTest;
import com.example.osivlab.QueryCountUtil;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Department;
import com.example.osivlab.dto.DepartmentDto;
import com.example.osivlab.dto.StoreDto;
import com.example.osivlab.dto.StoreProjection;
import com.example.osivlab.service.DepartmentService;
import com.example.osivlab.service.StoreService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Volumetry comparison: measures query counts across all solutions
 * with increasing data volumes.
 *
 * Produces tables that show how each solution scales.
 */
@ActiveProfiles({"test", "osiv-disabled"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VolumetryComparisonTest extends AbstractIntegrationTest {

    @Autowired private StoreService storeService;
    @Autowired private DepartmentService departmentService;
    @Autowired private TestDataFactory testDataFactory;
    @Autowired private EntityManagerFactory emf;

    private QueryCountUtil queryCounter;

    @BeforeEach
    void setUp() {
        testDataFactory.cleanAll();
        queryCounter = new QueryCountUtil(emf.unwrap(SessionFactory.class));
    }

    // =========================================================================
    // STORE VOLUMETRY (ManyToOne — N+1 scenario)
    // Each store has UNIQUE storeType, region, timezone → worst-case N+1
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Store volumetry: all solutions across 5, 25, 50, 100 stores (unique refs)")
    void storeVolumetry() {
        int[] volumes = {5, 25, 50, 100};

        List<long[]> entityGraphResults = new ArrayList<>();
        List<long[]> transactionalResults = new ArrayList<>();
        List<long[]> dtoProjectionResults = new ArrayList<>();

        for (int vol : volumes) {
            testDataFactory.cleanAll();
            testDataFactory.createStoresWithUniqueRelations(vol);

            // @EntityGraph
            queryCounter.clear();
            List<StoreDto> eg = storeService.getAllStoresWithEntityGraph();
            assertThat(eg).hasSize(vol);
            entityGraphResults.add(new long[]{vol, queryCounter.getPrepareStatementCount()});

            // @Transactional (N+1)
            queryCounter.clear();
            List<StoreDto> tx = storeService.getAllStoresTransactional();
            assertThat(tx).hasSize(vol);
            transactionalResults.add(new long[]{vol, queryCounter.getPrepareStatementCount()});

            // DTO Projection
            queryCounter.clear();
            List<StoreProjection> dto = storeService.getAllStoresProjection();
            assertThat(dto).hasSize(vol);
            dtoProjectionResults.add(new long[]{vol, queryCounter.getPrepareStatementCount()});
        }

        System.out.println();
        System.out.println("=== VOLUMETRIA: Store (3 ManyToOne, referencias UNICAS por store) ===");
        System.out.println("----------------------------------------------------------------------");
        System.out.println("  | Registros  | @EntityGraph   | @Transactional | DTO Projection    |");
        System.out.println("----------------------------------------------------------------------");
        for (int i = 0; i < volumes.length; i++) {
            System.out.printf("  | %4d       | %4d           | %4d           | %4d              |%n",
                    entityGraphResults.get(i)[0],
                    entityGraphResults.get(i)[1],
                    transactionalResults.get(i)[1],
                    dtoProjectionResults.get(i)[1]);
        }
        System.out.println("----------------------------------------------------------------------");
        System.out.println();
    }

    // =========================================================================
    // STORE VOLUMETRY WITH batch_fetch_size (separate profile needed)
    // We simulate by testing with shared refs to show contrast
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("Store volumetry: shared vs unique refs impact on @Transactional")
    void storeCardinalityImpact() {
        int[] volumes = {5, 25, 50, 100};

        List<long[]> sharedResults = new ArrayList<>();
        List<long[]> uniqueResults = new ArrayList<>();

        for (int vol : volumes) {
            // Shared references (2 of each type)
            testDataFactory.cleanAll();
            testDataFactory.createStores(vol);
            queryCounter.clear();
            List<StoreDto> shared = storeService.getAllStoresTransactional();
            assertThat(shared).hasSize(vol);
            sharedResults.add(new long[]{vol, queryCounter.getPrepareStatementCount()});

            // Unique references (1 per store)
            testDataFactory.cleanAll();
            testDataFactory.createStoresWithUniqueRelations(vol);
            queryCounter.clear();
            List<StoreDto> unique = storeService.getAllStoresTransactional();
            assertThat(unique).hasSize(vol);
            uniqueResults.add(new long[]{vol, queryCounter.getPrepareStatementCount()});
        }

        System.out.println();
        System.out.println("=== VOLUMETRIA: Impacto de cardinalidad en N+1 (@Transactional) ===");
        System.out.println("  Store con 3 ManyToOne -- refs compartidas vs unicas");
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  | Registros  | Refs compartidas (2) | Refs unicas (1 por store)    |");
        System.out.println("-----------------------------------------------------------------------");
        for (int i = 0; i < volumes.length; i++) {
            System.out.printf("  | %4d       | %4d                 | %4d                         |%n",
                    sharedResults.get(i)[0],
                    sharedResults.get(i)[1],
                    uniqueResults.get(i)[1]);
        }
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  Formula refs unicas: 1 + (N * 3)  |  Formula refs compartidas: 1 + distinct_refs");
        System.out.println();
    }

    // =========================================================================
    // DEPARTMENT VOLUMETRY (OneToMany — collection loading)
    // 1 Department, varying items per collection
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("Department volumetry: 1 dept, varying items per collection (3, 10, 25, 50)")
    void departmentCollectionVolumetry() {
        int[] itemsPerCol = {3, 10, 25, 50};

        List<long[]> transactionalResults = new ArrayList<>();
        List<long[]> splitResults = new ArrayList<>();

        for (int items : itemsPerCol) {
            testDataFactory.cleanAll();
            Department dept = testDataFactory.createDepartmentWithCollections(items);

            // @Transactional
            queryCounter.clear();
            DepartmentDto txDto = departmentService.getDepartmentTransactional(dept.getId());
            assertThat(txDto).isNotNull();
            transactionalResults.add(new long[]{items, queryCounter.getPrepareStatementCount()});

            // Split Queries
            queryCounter.clear();
            DepartmentDto splitDto = departmentService.getDepartmentSplitQueries(dept.getId());
            assertThat(splitDto).isNotNull();
            splitResults.add(new long[]{items, queryCounter.getPrepareStatementCount()});
        }

        System.out.println();
        System.out.println("=== VOLUMETRIA: Department (1 ManyToOne + 6 colecciones) ===");
        System.out.println("  1 departamento, variando items por coleccion");
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  | Items/coleccion | @Transactional   | Split Queries              |");
        System.out.println("-----------------------------------------------------------------------");
        for (int i = 0; i < itemsPerCol.length; i++) {
            System.out.printf("  | %4d            | %4d             | %4d                       |%n",
                    transactionalResults.get(i)[0],
                    transactionalResults.get(i)[1],
                    splitResults.get(i)[1]);
        }
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  @Transactional: 1 base + 1 region + 6 collections (constante)");
        System.out.println("  Split Queries:  6 JOIN FETCH + merges en L1 cache (constante)");
        System.out.println();
    }

    // =========================================================================
    // DEPARTMENT VOLUMETRY — Multiple departments (findAll)
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("Department volumetry: N departments x 5 items/col with @Transactional")
    void multipleDepartmentsVolumetry() {
        int[] deptCounts = {1, 5, 10, 25};

        List<long[]> results = new ArrayList<>();

        for (int count : deptCounts) {
            testDataFactory.cleanAll();
            testDataFactory.createDepartmentsWithCollections(count, 5);

            queryCounter.clear();
            List<DepartmentDto> depts = departmentService.getAllDepartmentsTransactional();
            assertThat(depts).hasSize(count);
            results.add(new long[]{count, queryCounter.getPrepareStatementCount()});
        }

        System.out.println();
        System.out.println("=== VOLUMETRIA: N departamentos x 5 items/coleccion ===");
        System.out.println("  @Transactional(readOnly=true) -- sin batch_fetch_size");
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  | Departamentos | Queries          | Total items cargados          |");
        System.out.println("-----------------------------------------------------------------------");
        for (long[] r : results) {
            long totalItems = r[0] * 5 * 6; // depts * items/col * 6 collections
            System.out.printf("  | %4d          | %4d             | %5d                         |%n",
                    r[0], r[1], totalItems);
        }
        System.out.println("-----------------------------------------------------------------------");
        System.out.println("  Formula: 1 base + N*region + N*6 colecciones");
        System.out.println();
    }
}
