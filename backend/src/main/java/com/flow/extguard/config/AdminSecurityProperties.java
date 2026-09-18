package com.flow.extguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * Shared-secret guard for the policy write endpoints.
 *
 * <p>When no token is configured the guard is disabled entirely, so a reviewer can
 * clone and run the project without setup. A startup warning makes that state loud.
 */
@ConfigurationProperties(prefix = "extguard.admin")
public class AdminSecurityProperties {

    /** Expected value of the X-Admin-Token header. Blank disables the guard. */
    private String token = "";

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean isEnabled() {
        return StringUtils.hasText(token);
    }
}
