package com.flow.extguard;

import com.flow.extguard.config.AdminSecurityProperties;
import com.flow.extguard.config.CorsProperties;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableConfigurationProperties({
        StorageProperties.class,
        PolicyProperties.class,
        AdminSecurityProperties.class,
        CorsProperties.class
})
public class ExtGuardApplication {

    /** The profile that migrates the schema and exits, instead of serving. */
    static final String MIGRATE_PROFILE = "migrate";

    public static void main(String[] args) {
        ConfigurableApplicationContext context =
                new SpringApplication(ExtGuardApplication.class).run(args);

        // Under the migrate profile this process is a deployment step, not a
        // server: Flyway has run by the time the context is up, so the work is
        // done and the container must exit for the next one to start.
        //
        // The exit is explicit rather than left to the JVM. Waiting for the last
        // non-daemon thread to finish would make termination depend on which
        // beans happen to start a thread pool -- and the failure mode is not a
        // crash but a container that never exits, which holds the whole deploy
        // behind it. Verified: running this jar with the web server off and no
        // explicit exit does not terminate.
        if (context.getEnvironment().matchesProfiles(MIGRATE_PROFILE)) {
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
