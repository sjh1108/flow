package com.flow.extguard.common;

import com.flow.extguard.policy.service.ActorInfo;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the audit actor and client IP from a request.
 */
public final class RequestActors {

    /** Set by {@code AdminTokenFilter} once a valid token has been presented. */
    public static final String ACTOR_ATTRIBUTE = "extguard.actor";

    private RequestActors() {
    }

    public static ActorInfo from(HttpServletRequest request) {
        Object actor = request.getAttribute(ACTOR_ATTRIBUTE);
        String name = actor instanceof String s ? s : "anonymous";
        return new ActorInfo(name, clientIp(request), request.getHeader("User-Agent"));
    }

    /**
     * Honours {@code X-Forwarded-For} because the service runs behind a reverse
     * proxy. The header is client-controllable, so this value is treated as a
     * logging hint and never as an authorisation input.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) {
                return first.length() > 45 ? first.substring(0, 45) : first;
            }
        }
        return request.getRemoteAddr();
    }
}
