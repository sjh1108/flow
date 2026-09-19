package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.flow.extguard.config.SchedulingConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * The migration process must not also be a running application.
 *
 * <p>Separating the two is what makes the database accounts mean anything: the
 * container that migrates holds the migrator's credentials, the one that serves
 * holds only the application account's, and neither holds the other's. That only
 * works if the migrating process is a deployment step that finishes.
 *
 * <p>What is pinned here is the context shape. That the process actually
 * terminates is a property of {@code main}, which a {@code @SpringBootTest}
 * never calls -- it builds the context directly. That one is verified by running
 * the jar; see the notes in {@code tasks/todo.md}.
 */
class MigrateProfileTest {

    @Nested
    @SpringBootTest
    @ActiveProfiles({"test", "migrate"})
    @DisplayName("under the migrate profile")
    class UnderMigrateProfile {

        @Autowired
        private ApplicationContext context;

        /**
         * A reclaim job firing inside a deployment step -- with the application
         * container about to start on the same data -- is work nobody asked for.
         */
        @Test
        @DisplayName("scheduling is off, so no reclaim job runs mid-deployment")
        void schedulingIsDisabled() {
            assertThat(context.getBeanNamesForType(SchedulingConfig.class))
                    .as("the migrate process migrates and stops; it does not run jobs")
                    .isEmpty();
        }

        /**
         * Flyway is what this process exists for, so it is stated in the profile
         * rather than inherited -- the deployment must not depend on a default
         * staying true.
         */
        @Test
        @DisplayName("Flyway is on, because migrating is the whole job")
        void flywayIsEnabled() {
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled"))
                    .isEqualTo("true");
        }
    }

    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @DisplayName("without it")
    class WithoutMigrateProfile {

        @Autowired
        private ApplicationContext context;

        /**
         * The counterpart assertion. Without it, a mistake that disabled
         * scheduling everywhere would still pass the test above, and the
         * retention job would silently never run.
         */
        @Test
        @DisplayName("scheduling is on, so retention and orphan sweeps still run")
        void schedulingIsEnabled() {
            assertThat(context.getBeanNamesForType(SchedulingConfig.class))
                    .as("the serving application is what actually reclaims disk space")
                    .isNotEmpty();
        }
    }
}
