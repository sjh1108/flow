package com.flow.extguard.config;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * CORS for the Vercel-hosted frontend.
 *
 * <p>This is a {@link CorsFilter} rather than {@code WebMvcConfigurer.addCorsMappings},
 * and the difference is not stylistic. {@code addCorsMappings} is applied by the
 * handler mapping <em>inside</em> {@code DispatcherServlet}, so it only reaches
 * responses the servlet produces. {@link AdminTokenFilter} rejects an
 * unauthenticated policy write from the filter chain, before the servlet runs: under
 * the old arrangement that 401 went out with no {@code Access-Control-Allow-Origin},
 * the browser blocked it, and the page reported a network failure instead of "관리자
 * 토큰이 필요합니다". The preflight succeeded and only the real request was lost, which
 * is what made it look like the server was broken.
 *
 * <p>As a filter it runs before the rest of the chain, so an allowed cross-origin
 * request carries its CORS headers even when a later filter short-circuits the
 * response. It handles only what matches {@code /api/**}; a request from an origin
 * outside the allow-list is rejected with 403 and gets no
 * {@code Access-Control-Allow-Origin} — which is the point of an allow-list.
 *
 * <p>Credentials are not allowed. The admin guard uses a header rather than a cookie,
 * so there is no reason to open credentialed cross-origin requests and every reason
 * not to.
 */
@Configuration
public class WebConfig {

    private final CorsProperties corsProperties;

    public WebConfig(CorsProperties corsProperties) {
        this.corsProperties = corsProperties;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(corsProperties.getAllowedOrigins()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-Admin-Token"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        // Ahead of every other filter. Anything that rejects a request from the chain
        // still needs its response to be readable by the browser that asked.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
