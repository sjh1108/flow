package com.flow.extguard.config;

import com.flow.extguard.common.ApiError;
import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.common.RequestActors;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Requires a shared secret on the endpoints that change policy.
 *
 * <p>Reads stay open so the upload page works for anyone, while writes -- and the
 * audit trail -- need {@code X-Admin-Token}. Without this, publishing the app would
 * let anyone who knows the URL rewrite the blocking policy.
 *
 * <p>If no token is configured the guard disables itself, so the project can be
 * cloned and run with no setup. A startup warning makes that state visible.
 *
 * <p>This filter must run <em>after</em> the CORS filter in {@link WebConfig}. It
 * answers from the filter chain rather than letting the request reach the servlet, so
 * whatever adds {@code Access-Control-Allow-Origin} has to have run already --
 * otherwise the browser discards this 401 and the page reports a network failure
 * instead of a permission problem. The order is stated rather than inherited so that
 * a later edit has to argue with this comment.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminTokenFilter.class);
    private static final String HEADER = "X-Admin-Token";
    private static final String POLICY_PREFIX = "/api/v1/policy";
    private static final String AUDIT_PATH = "/api/v1/policy/audit";

    private final AdminSecurityProperties properties;
    private final ObjectMapper objectMapper;

    public AdminTokenFilter(AdminSecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void warnIfDisabled() {
        if (!properties.isEnabled()) {
            log.warn("EXTGUARD_ADMIN_TOKEN is not set: policy write endpoints are UNAUTHENTICATED. "
                    + "Set it before exposing this service publicly.");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.isEnabled() || !requiresAdmin(request)) {
            chain.doFilter(request, response);
            return;
        }

        String presented = request.getHeader(HEADER);
        if (presented == null || !constantTimeEquals(presented, properties.getToken())) {
            log.warn("Rejected unauthenticated policy change: {} {} from {}",
                    request.getMethod(), request.getRequestURI(), RequestActors.clientIp(request));
            writeUnauthorized(response);
            return;
        }

        request.setAttribute(RequestActors.ACTOR_ATTRIBUTE, "admin-token");
        chain.doFilter(request, response);
    }

    private static boolean requiresAdmin(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(POLICY_PREFIX)) {
            return false;
        }
        // CORS preflight carries no custom headers by definition, so demanding the
        // token here would break every browser request before it was even sent.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        // Reading the policy is open; reading the audit trail is not.
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return path.startsWith(AUDIT_PATH);
        }
        return true;
    }

    /** Length-independent comparison, so timing cannot reveal the token. */
    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(ApiErrorCode.UNAUTHORIZED.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiError.of(
                ApiErrorCode.UNAUTHORIZED,
                ApiErrorCode.UNAUTHORIZED.message(),
                "X-Admin-Token 헤더가 없거나 올바르지 않습니다.")));
    }
}
