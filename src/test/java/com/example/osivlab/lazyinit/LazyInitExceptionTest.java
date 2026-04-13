package com.example.osivlab.lazyinit;

import com.example.osivlab.AbstractWebIntegrationTest;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Employee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 2: Demonstrates LazyInitializationException when OSIV is disabled.
 *
 * Tests go through MockMvc so the OSIV interceptor is active during HTTP requests.
 * - GET /api/employees/{id}              → no @Transactional, relies on OSIV
 * - GET /api/employees/{id}/transactional → @Transactional(readOnly=true)
 * - GET /api/employees/{id}/entity-graph  → @EntityGraph
 */
class LazyInitExceptionTest {

    @Nested
    @ActiveProfiles({"test", "osiv-enabled"})
    @DisplayName("OSIV=true: lazy loading works silently outside transaction")
    class WithOsivEnabled extends AbstractWebIntegrationTest {

        @Autowired
        private TestDataFactory testDataFactory;
        private Employee employee;

        @BeforeEach
        void setUp() {
            testDataFactory.cleanAll();
            employee = testDataFactory.createEmployeeWithRolesAndPermissions();
        }

        @Test
        @DisplayName("GET /employees/{id} → 200 OK (OSIV keeps session open for lazy roles)")
        void lazyLoadingWorksWithOsiv() throws Exception {
            mockMvc.perform(get("/api/employees/{id}", employee.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("John Doe"))
                    .andExpect(jsonPath("$.roles").isArray())
                    .andExpect(jsonPath("$.roles.length()").value(2));
        }
    }

    @Nested
    @ActiveProfiles({"test", "osiv-disabled"})
    @DisplayName("OSIV=false: lazy loading outside transaction throws exception")
    class WithOsivDisabled extends AbstractWebIntegrationTest {

        @Autowired
        private TestDataFactory testDataFactory;
        private Employee employee;

        @BeforeEach
        void setUp() {
            testDataFactory.cleanAll();
            employee = testDataFactory.createEmployeeWithRolesAndPermissions();
        }

        @Test
        @DisplayName("GET /employees/{id} → LazyInitializationException (no session)")
        void lazyLoadingFailsWithoutOsiv() {
            assertThatThrownBy(() ->
                    mockMvc.perform(get("/api/employees/{id}", employee.getId())))
                    .rootCause()
                    .isInstanceOf(org.hibernate.LazyInitializationException.class);
        }

        @Test
        @DisplayName("GET /employees/{id}/transactional → 200 OK (@Transactional keeps session)")
        void transactionalFixesLazyLoading() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/transactional", employee.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("John Doe"))
                    .andExpect(jsonPath("$.roles.length()").value(2));
        }

        @Test
        @DisplayName("GET /employees/{id}/entity-graph → 200 OK (single JOIN query)")
        void entityGraphFixesLazyLoading() throws Exception {
            mockMvc.perform(get("/api/employees/{id}/entity-graph", employee.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("John Doe"))
                    .andExpect(jsonPath("$.roles.length()").value(2));
        }
    }
}
