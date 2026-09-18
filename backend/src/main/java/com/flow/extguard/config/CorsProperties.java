package com.flow.extguard.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins allowed to call the API from a browser.
 *
 * <p>Credentials are never allowed: the admin guard uses a header, not a cookie,
 * so there is no reason to widen CORS to credentialed requests.
 */
@ConfigurationProperties(prefix = "extguard.cors")
public class CorsProperties {

    private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:3000");

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
