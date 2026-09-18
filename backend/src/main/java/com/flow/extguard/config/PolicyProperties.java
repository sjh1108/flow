package com.flow.extguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the extension policy itself.
 */
@ConfigurationProperties(prefix = "extguard.policy")
public class PolicyProperties {

    /** Maximum number of custom extensions that may be registered. */
    private int maxCustomExtensions = 200;

    /** Maximum length of a single normalized extension. */
    private int maxExtensionLength = 20;

    /**
     * When true, every segment of a filename's extension chain is checked against
     * the blocklist, so {@code invoice.pdf.exe} is rejected for {@code exe}.
     *
     * <p>Turning this off checks only the final segment. That is more permissive
     * and reopens the classic double-extension bypass; see docs/01-decisions.md.
     */
    private boolean checkFullExtensionChain = true;

    public int getMaxCustomExtensions() {
        return maxCustomExtensions;
    }

    public void setMaxCustomExtensions(int maxCustomExtensions) {
        this.maxCustomExtensions = maxCustomExtensions;
    }

    public int getMaxExtensionLength() {
        return maxExtensionLength;
    }

    public void setMaxExtensionLength(int maxExtensionLength) {
        this.maxExtensionLength = maxExtensionLength;
    }

    public boolean isCheckFullExtensionChain() {
        return checkFullExtensionChain;
    }

    public void setCheckFullExtensionChain(boolean checkFullExtensionChain) {
        this.checkFullExtensionChain = checkFullExtensionChain;
    }
}
