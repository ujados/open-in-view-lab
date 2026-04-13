package com.example.osivlab;

import com.example.osivlab.domain.*;
import com.example.osivlab.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TestDataFactory {

    private final OrderRepository orderRepository;
    private final EmployeeRepository employeeRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final StoreRepository storeRepository;
    private final StoreTypeRepository storeTypeRepository;
    private final RegionRepository regionRepository;
    private final TimezoneRepository timezoneRepository;
    private final DepartmentRepository departmentRepository;
    private final VendorRepository vendorRepository;
    private final WarehouseRepository warehouseRepository;
    private final EntityManager entityManager;

    /**
     * Bulk insert stores with UNIQUE references using PostgreSQL generate_series.
     * Inserts N stores + N types + N regions + N timezones in 4 SQL statements.
     * 1M records in ~2 seconds vs minutes with row-by-row JPA.
     */
    @Transactional
    public void bulkInsertStoresWithUniqueRefs(int count) {
        entityManager.createNativeQuery(
                "INSERT INTO store_types (id, name) SELECT i, 'Type-' || i FROM generate_series(1, :n) AS i")
                .setParameter("n", count).executeUpdate();
        entityManager.createNativeQuery(
                "INSERT INTO regions (id, code, name) SELECT i, 'R' || i, 'Region-' || i FROM generate_series(1, :n) AS i")
                .setParameter("n", count).executeUpdate();
        entityManager.createNativeQuery(
                "INSERT INTO timezones (id, display_name, zone_id) SELECT i, 'TZ ' || i, 'TZ/' || i FROM generate_series(1, :n) AS i")
                .setParameter("n", count).executeUpdate();
        entityManager.createNativeQuery(
                "INSERT INTO stores (id, name, address, store_type_id, region_id, timezone_id) " +
                "SELECT i, 'Store ' || i, 'Addr ' || i, i, i, i FROM generate_series(1, :n) AS i")
                .setParameter("n", count).executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Bulk insert stores with SHARED references (2 of each type) using generate_series.
     */
    @Transactional
    public void bulkInsertStoresWithSharedRefs(int count) {
        entityManager.createNativeQuery("INSERT INTO store_types (id, name) VALUES (1, 'Retail'), (2, 'Warehouse')").executeUpdate();
        entityManager.createNativeQuery("INSERT INTO regions (id, code, name) VALUES (1, 'E', 'East'), (2, 'W', 'West')").executeUpdate();
        entityManager.createNativeQuery("INSERT INTO timezones (id, display_name, zone_id) VALUES (1, 'UTC', 'UTC'), (2, 'EST', 'America/New_York')").executeUpdate();
        entityManager.createNativeQuery(
                "INSERT INTO stores (id, name, address, store_type_id, region_id, timezone_id) " +
                "SELECT i, 'Store ' || i, 'Addr ' || i, (i % 2) + 1, (i % 2) + 1, (i % 2) + 1 FROM generate_series(1, :n) AS i")
                .setParameter("n", count).executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Bulk insert N departments, each with `itemsPerCol` items in 6 collections.
     * Uses PostgreSQL generate_series for instant seeding.
     */
    @Transactional
    public void bulkInsertDepartmentsWithCollections(int deptCount, int itemsPerCol) {
        // 1 shared region
        entityManager.createNativeQuery("INSERT INTO regions (id, code, name) VALUES (1, 'C', 'Central') ON CONFLICT DO NOTHING").executeUpdate();

        // departments
        entityManager.createNativeQuery(
                "INSERT INTO departments (id, name, code, region_id) " +
                "SELECT i, 'Dept ' || i, 'D' || i, 1 FROM generate_series(1, :n) AS i")
                .setParameter("n", deptCount).executeUpdate();

        // 6 collections per department
        entityManager.createNativeQuery(
                "INSERT INTO employees (id, name, email, password, active, department_id) " +
                "SELECT (d-1) * :items + i, 'Emp-' || d || '-' || i, 'emp' || d || '_' || i || '@test.com', 'pass', true, d " +
                "FROM generate_series(1, :n) AS d, generate_series(1, :items) AS i")
                .setParameter("n", deptCount).setParameter("items", itemsPerCol).executeUpdate();

        entityManager.createNativeQuery(
                "INSERT INTO projects (id, name, code, department_id) " +
                "SELECT (d-1) * :items + i, 'Proj-' || d || '-' || i, 'P' || d || i, d " +
                "FROM generate_series(1, :n) AS d, generate_series(1, :items) AS i")
                .setParameter("n", deptCount).setParameter("items", itemsPerCol).executeUpdate();

        entityManager.createNativeQuery(
                "INSERT INTO budgets (id, name, amount, year, department_id) " +
                "SELECT (d-1) * :items + i, 'Budget-' || d || '-' || i, 1000, 2024, d " +
                "FROM generate_series(1, :n) AS d, generate_series(1, :items) AS i")
                .setParameter("n", deptCount).setParameter("items", itemsPerCol).executeUpdate();

        entityManager.createNativeQuery(
                "INSERT INTO equipment (id, name, serial_number, department_id) " +
                "SELECT (d-1) * :items + i, 'Equip-' || d || '-' || i, 'SN-' || d || '-' || i, d " +
                "FROM generate_series(1, :n) AS d, generate_series(1, :items) AS i")
                .setParameter("n", deptCount).setParameter("items", itemsPerCol).executeUpdate();

        entityManager.createNativeQuery(
                "INSERT INTO policies (id, title, content, department_id) " +
                "SELECT (d-1) * :items + i, 'Policy-' || d || '-' || i, 'Content', d " +
                "FROM generate_series(1, :n) AS d, generate_series(1, :items) AS i")
                .setParameter("n", deptCount).setParameter("items", itemsPerCol).executeUpdate();

        entityManager.createNativeQuery(
                "INSERT INTO documents (id, title, url, department_id) " +
                "SELECT (d-1) * :items + i, 'Doc-' || d || '-' || i, 'https://docs/' || d || '/' || i, d " +
                "FROM generate_series(1, :n) AS d, generate_series(1, :items) AS i")
                .setParameter("n", deptCount).setParameter("items", itemsPerCol).executeUpdate();

        entityManager.flush();
        entityManager.clear();
    }

    @Transactional
    public void cleanAll() {
        entityManager.createNativeQuery("SET CONSTRAINTS ALL DEFERRED").executeUpdate();
        // Delete in dependency order
        entityManager.createNativeQuery("DELETE FROM employee_roles").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM role_permissions").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM store_employees").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM order_details").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM order_items").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM documents").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM policies").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM equipment").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM budgets").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM projects").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM warehouses").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM employees").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM departments").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM products").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM categories").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM stores").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM store_types").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM regions").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM timezones").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM roles").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM permissions").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM vendors").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM orders").executeUpdate();
        entityManager.flush();
    }

    @Transactional
    public Order createOrder() {
        return orderRepository.save(Order.builder()
                .code("ORD-001")
                .description("Test order")
                .status(OrderStatus.PENDING)
                .build());
    }

    @Transactional
    public List<Order> createOrders(int count) {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            orders.add(orderRepository.save(Order.builder()
                    .code("ORD-" + String.format("%03d", i))
                    .description("Test order " + i)
                    .status(OrderStatus.PENDING)
                    .build()));
        }
        return orders;
    }

    @Transactional
    public Employee createEmployeeWithRolesAndPermissions() {
        Permission read = permissionRepository.save(Permission.builder().name("READ").description("Read access").build());
        Permission write = permissionRepository.save(Permission.builder().name("WRITE").description("Write access").build());
        Permission delete = permissionRepository.save(Permission.builder().name("DELETE").description("Delete access").build());

        Role admin = roleRepository.save(Role.builder().name("ADMIN").permissions(Set.of(read, write, delete)).build());
        Role viewer = roleRepository.save(Role.builder().name("VIEWER").permissions(Set.of(read)).build());

        return employeeRepository.save(Employee.builder()
                .name("John Doe")
                .email("john@example.com")
                .password("secret123")
                .active(true)
                .roles(Set.of(admin, viewer))
                .build());
    }

    @Transactional
    public List<Product> createProducts(int count) {
        Category electronics = categoryRepository.save(Category.builder().name("Electronics").description("Electronic devices").build());
        Category clothing = categoryRepository.save(Category.builder().name("Clothing").description("Apparel").build());

        List<Product> products = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            products.add(productRepository.save(Product.builder()
                    .name("Product " + i)
                    .sku("SKU-" + String.format("%03d", i))
                    .price(BigDecimal.valueOf(10 + i))
                    .category(i % 2 == 0 ? electronics : clothing)
                    .build()));
        }
        return products;
    }

    @Transactional
    public List<Store> createStores(int count) {
        StoreType retail = storeTypeRepository.save(StoreType.builder().name("Retail").build());
        StoreType warehouse = storeTypeRepository.save(StoreType.builder().name("Warehouse").build());
        Region east = regionRepository.save(Region.builder().name("East").code("E").build());
        Region west = regionRepository.save(Region.builder().name("West").code("W").build());
        Timezone utc = timezoneRepository.save(Timezone.builder().zoneId("UTC").displayName("UTC").build());
        Timezone est = timezoneRepository.save(Timezone.builder().zoneId("America/New_York").displayName("Eastern").build());

        List<Store> stores = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            stores.add(storeRepository.save(Store.builder()
                    .name("Store " + i)
                    .address("Address " + i)
                    .storeType(i % 2 == 0 ? retail : warehouse)
                    .region(i % 2 == 0 ? east : west)
                    .timezone(i % 2 == 0 ? utc : est)
                    .build()));
        }
        return stores;
    }

    /**
     * Creates stores where EACH store has its own UNIQUE storeType, region, and timezone.
     * This maximizes N+1: every lazy proxy points to a different entity.
     */
    @Transactional
    public List<Store> createStoresWithUniqueRelations(int count) {
        List<Store> stores = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StoreType type = storeTypeRepository.save(StoreType.builder().name("Type-" + i).build());
            Region region = regionRepository.save(Region.builder().name("Region-" + i).code("R" + i).build());
            Timezone tz = timezoneRepository.save(Timezone.builder().zoneId("TZ/" + i).displayName("TZ " + i).build());
            stores.add(storeRepository.save(Store.builder()
                    .name("Store " + i)
                    .address("Address " + i)
                    .storeType(type)
                    .region(region)
                    .timezone(tz)
                    .build()));
        }
        return stores;
    }

    @Transactional
    public Department createDepartmentWithCollections(int itemsPerCollection) {
        Region region = regionRepository.save(Region.builder().name("Central").code("C").build());

        Department dept = Department.builder()
                .name("Engineering")
                .code("ENG")
                .region(region)
                .employees(new ArrayList<>())
                .projects(new ArrayList<>())
                .budgets(new ArrayList<>())
                .equipment(new ArrayList<>())
                .policies(new ArrayList<>())
                .documents(new ArrayList<>())
                .build();
        dept = departmentRepository.save(dept);

        for (int i = 0; i < itemsPerCollection; i++) {
            dept.getEmployees().add(Employee.builder()
                    .name("Employee " + i).email("emp" + i + "@test.com")
                    .password("pass").active(true).department(dept).build());
            dept.getProjects().add(Project.builder()
                    .name("Project " + i).code("P" + i).department(dept).build());
            dept.getBudgets().add(Budget.builder()
                    .name("Budget " + i).amount(BigDecimal.valueOf(1000 * (i + 1)))
                    .year(2024).department(dept).build());
            dept.getEquipment().add(Equipment.builder()
                    .name("Equipment " + i).serialNumber("SN-" + i).department(dept).build());
            dept.getPolicies().add(Policy.builder()
                    .title("Policy " + i).content("Content " + i).department(dept).build());
            dept.getDocuments().add(Document.builder()
                    .title("Document " + i).url("https://docs.example.com/" + i).department(dept).build());
        }

        return departmentRepository.save(dept);
    }

    @Transactional
    public List<Department> createDepartmentsWithCollections(int deptCount, int itemsPerCollection) {
        List<Department> departments = new ArrayList<>();
        Region region = regionRepository.save(Region.builder().name("Multi").code("M").build());

        for (int d = 0; d < deptCount; d++) {
            Department dept = Department.builder()
                    .name("Department " + d)
                    .code("D" + d)
                    .region(region)
                    .employees(new ArrayList<>())
                    .projects(new ArrayList<>())
                    .budgets(new ArrayList<>())
                    .equipment(new ArrayList<>())
                    .policies(new ArrayList<>())
                    .documents(new ArrayList<>())
                    .build();
            dept = departmentRepository.save(dept);

            for (int i = 0; i < itemsPerCollection; i++) {
                dept.getEmployees().add(Employee.builder()
                        .name("Emp-" + d + "-" + i).email("emp" + d + "_" + i + "@test.com")
                        .password("pass").active(true).department(dept).build());
                dept.getProjects().add(Project.builder()
                        .name("Proj-" + d + "-" + i).code("P" + d + i).department(dept).build());
                dept.getBudgets().add(Budget.builder()
                        .name("Budget-" + d + "-" + i).amount(BigDecimal.valueOf(1000))
                        .year(2024).department(dept).build());
                dept.getEquipment().add(Equipment.builder()
                        .name("Equip-" + d + "-" + i).serialNumber("SN-" + d + "-" + i).department(dept).build());
                dept.getPolicies().add(Policy.builder()
                        .title("Policy-" + d + "-" + i).content("Content").department(dept).build());
                dept.getDocuments().add(Document.builder()
                        .title("Doc-" + d + "-" + i).url("https://docs.example.com/" + d + "/" + i).department(dept).build());
            }

            departments.add(departmentRepository.save(dept));
        }
        return departments;
    }
}
