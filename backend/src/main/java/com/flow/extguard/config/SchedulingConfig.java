package com.flow.extguard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the scheduler that drives {@code StorageMaintenanceService}.
 *
 * <p>Spring's own scheduler is enough for it: the work is idempotent, runs once
 * a day, and this deploys as a single instance, so Quartz would add a dependency
 * and a coordination table for something that needs no coordinating.
 *
 * <p>Excluded from the {@code migrate} profile. That process exists to run the
 * migration and stop, and a reclaim job firing inside a deployment step -- as a
 * second container is about to start on the same data -- is work nobody asked
 * for. This is hygiene, not the reason that process terminates; see
 * {@code ExtGuardApplication} for that.
 */
@Configuration
@Profile("!migrate")
@EnableScheduling
public class SchedulingConfig {
}
