package com.example.osivlab.dirtychecking;

import com.example.osivlab.AbstractWebIntegrationTest;
import com.example.osivlab.TestDataFactory;
import com.example.osivlab.domain.Employee;
import com.example.osivlab.repository.EmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 4: Demonstrates how OSIV masks the dirty checking bug.
 *
 * ChangeEmployeePasswordService.changePasswordBuggy() has NO @Transactional.
 * It sets the password, then does a count() query. With OSIV, the count()
 * triggers auto-flush → password persisted. Without OSIV, entity is detached → lost.
 */
class PasswordBugTest {

    @Nested
    @ActiveProfiles({"test", "osiv-enabled"})
    @DisplayName("OSIV=true: auto-flush hides the missing save()")
    class WithOsivEnabled extends AbstractWebIntegrationTest {

        @Autowired private EmployeeRepository employeeRepository;
        @Autowired private TestDataFactory testDataFactory;
        private Employee employee;

        @BeforeEach
        void setUp() {
            testDataFactory.cleanAll();
            employee = testDataFactory.createEmployeeWithRolesAndPermissions();
        }

        @Test
        @DisplayName("Buggy method + OSIV=true → password IS persisted (auto-flush hides bug)")
        void buggyMethodPasswordPersistedWithOsiv() throws Exception {
            String newPassword = "newSecurePassword123";

            mockMvc.perform(put("/api/employees/{id}/password-buggy", employee.getId())
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(newPassword))
                    .andExpect(status().isOk());

            Employee reloaded = employeeRepository.findById(employee.getId()).orElseThrow();
            assertThat(reloaded.getPassword())
                    .as("OSIV auto-flush persists password even without save()")
                    .isEqualTo(newPassword);
        }
    }

    @Nested
    @ActiveProfiles({"test", "osiv-disabled"})
    @DisplayName("OSIV=false: missing save() causes password to be lost")
    class WithOsivDisabled extends AbstractWebIntegrationTest {

        @Autowired private EmployeeRepository employeeRepository;
        @Autowired private TestDataFactory testDataFactory;
        private Employee employee;

        @BeforeEach
        void setUp() {
            testDataFactory.cleanAll();
            employee = testDataFactory.createEmployeeWithRolesAndPermissions();
        }

        @Test
        @DisplayName("Buggy method + OSIV=false → password is LOST (bug exposed)")
        void buggyMethodPasswordLostWithoutOsiv() throws Exception {
            String newPassword = "newSecurePassword123";
            String originalPassword = employee.getPassword();

            mockMvc.perform(put("/api/employees/{id}/password-buggy", employee.getId())
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(newPassword))
                    .andExpect(status().isOk());

            Employee reloaded = employeeRepository.findById(employee.getId()).orElseThrow();
            assertThat(reloaded.getPassword())
                    .as("Without OSIV, password change is lost — entity was detached")
                    .isEqualTo(originalPassword);
        }

        @Test
        @DisplayName("Correct method (saveAndFlush) → password persisted regardless of OSIV")
        void correctMethodAlwaysWorks() throws Exception {
            String newPassword = "newSecurePassword123";

            mockMvc.perform(put("/api/employees/{id}/password-correct", employee.getId())
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(newPassword))
                    .andExpect(status().isOk());

            Employee reloaded = employeeRepository.findById(employee.getId()).orElseThrow();
            assertThat(reloaded.getPassword())
                    .as("Explicit saveAndFlush always works")
                    .isEqualTo(newPassword);
        }
    }
}
