package com.flow.extguard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Where accepted uploads are written, and the hard limits applied to them.
 *
 * <p>The storage root deliberately lives outside any served directory: this
 * application never hands an uploaded file back out over HTTP.
 */
@ConfigurationProperties(prefix = "extguard.storage")
public class StorageProperties {

    /** Filesystem root for accepted uploads. Must not be under a served path. */
    private String root = "/var/lib/extguard/files";

    /** Rejected above this size. Also enforced by the servlet container earlier in the request. */
    private DataSize maxFileSize = DataSize.ofMegabytes(20);

    /** Maximum number of files accepted in a single multipart request. */
    private int maxFilesPerRequest = 10;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public int getMaxFilesPerRequest() {
        return maxFilesPerRequest;
    }

    public void setMaxFilesPerRequest(int maxFilesPerRequest) {
        this.maxFilesPerRequest = maxFilesPerRequest;
    }
}
