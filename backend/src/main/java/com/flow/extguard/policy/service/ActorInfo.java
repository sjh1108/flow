package com.flow.extguard.policy.service;

/**
 * Who made a policy change, as far as the system can tell.
 *
 * <p>There is no user login in scope, so {@code name} records the authorisation
 * mode rather than an identity: "admin-token" when the shared secret was
 * presented, "anonymous" when the guard is switched off. The IP and user agent
 * are what make an entry traceable in practice.
 */
public record ActorInfo(String name, String ip, String userAgent) {

    public static ActorInfo anonymous() {
        return new ActorInfo("anonymous", null, null);
    }
}
