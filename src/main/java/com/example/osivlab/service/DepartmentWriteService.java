package com.example.osivlab.service;

import com.example.osivlab.domain.*;
import com.example.osivlab.repository.DepartmentRepository;
import com.example.osivlab.repository.RegionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class DepartmentWriteService {

    private final DepartmentRepository departmentRepository;
    private final RegionRepository regionRepository;
    private final EntityManager entityManager;

    /**
     * Creates a Department with N items per collection using JPA cascade.
     * Without jdbc.batch_size, each INSERT is a separate JDBC statement.
     * With jdbc.batch_size=50, Hibernate batches them into groups of 50.
     */
    @Transactional
    public Department createDepartmentWithCascade(int itemsPerCollection) {
        Region region = regionRepository.save(Region.builder().name("Write-Test").code("WT").build());

        Department dept = Department.builder()
                .name("Write Department")
                .code("WD")
                .region(region)
                .build();

        for (int i = 0; i < itemsPerCollection; i++) {
            dept.getEmployees().add(Employee.builder()
                    .name("Emp " + i).email("emp_w" + i + "@test.com")
                    .password("pass").active(true).department(dept).build());
            dept.getProjects().add(Project.builder()
                    .name("Proj " + i).code("WP" + i).department(dept).build());
            dept.getBudgets().add(Budget.builder()
                    .name("Budget " + i).amount(BigDecimal.valueOf(1000))
                    .year(2024).department(dept).build());
            dept.getEquipment().add(Equipment.builder()
                    .name("Equip " + i).serialNumber("WSN-" + i).department(dept).build());
            dept.getPolicies().add(Policy.builder()
                    .title("Policy " + i).content("Content").department(dept).build());
            dept.getDocuments().add(Document.builder()
                    .title("Doc " + i).url("https://docs/" + i).department(dept).build());
        }

        return departmentRepository.save(dept);
    }

    /**
     * Bulk insert N independent entities using EntityManager directly.
     * Flushes and clears every `batchSize` entities to avoid memory bloat.
     */
    @Transactional
    public void bulkInsertOrders(int count) {
        for (int i = 0; i < count; i++) {
            Order order = Order.builder()
                    .code("BULK-" + i)
                    .description("Bulk order " + i)
                    .status(OrderStatus.PENDING)
                    .build();
            entityManager.persist(order);

            if (i > 0 && i % 50 == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
    }
}
