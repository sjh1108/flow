package com.flow.extguard;

import com.flow.extguard.config.AdminSecurityProperties;
import com.flow.extguard.config.CorsProperties;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling drives one job: StorageMaintenanceService reclaiming disk space.
// Spring's own scheduler is enough for it -- the work is idempotent, runs once a
// day, and this deploys as a single instance, so Quartz would add a dependency
// and a table to coordinate something that needs no coordinating.
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
        StorageProperties.class,
        PolicyProperties.class,
        AdminSecurityProperties.class,
        CorsProperties.class
})
public class ExtGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtGuardApplication.class, args);
    }
}
