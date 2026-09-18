package com.flow.extguard.policy.domain;

import java.util.List;
import java.util.Set;

/**
 * The seven fixed extensions, as a code constant.
 *
 * <p>This is deliberately <em>not</em> derived from the database. The policy screen
 * renders this list and joins database state onto it, so deleting a row from
 * {@code fixed_extension_state} cannot make a fixed extension disappear from the
 * UI -- it merely reads back as unblocked. The fixed list is code; only the
 * toggle state is data.
 *
 * <p>Kept in sync with the {@code ck_fixed_whitelist} and {@code ck_custom_not_fixed}
 * CHECK constraints in {@code V1__init.sql}; {@code FixedExtensionsIntegrityTest}
 * fails if they drift.
 */
public final class FixedExtensions {

    public static final List<String> ALL = List.of("bat", "cmd", "com", "cpl", "exe", "scr", "js");

    private static final Set<String> LOOKUP = Set.copyOf(ALL);

    private FixedExtensions() {
    }

    /** @param normalizedExtension a value already through {@code ExtensionNormalizer} */
    public static boolean contains(String normalizedExtension) {
        return LOOKUP.contains(normalizedExtension);
    }
}
