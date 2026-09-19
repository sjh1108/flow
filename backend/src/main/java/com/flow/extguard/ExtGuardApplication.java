package com.flow.extguard;

import com.flow.extguard.config.AdminSecurityProperties;
import com.flow.extguard.config.CorsProperties;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
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
