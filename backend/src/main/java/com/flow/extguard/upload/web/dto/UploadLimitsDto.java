package com.flow.extguard.upload.web.dto;

import com.flow.extguard.config.StorageProperties;

/**
 * The request-shape limits the upload screen has to respect.
 *
 * <p>Published for the same reason {@code PolicyResponse.LimitsDto} is: so the
 * client never hardcodes a number that lives in {@code application.yml}. The
 * upload screen was the one place still doing that -- "한 번에 최대 10개 · 파일당
 * 최대 20MB" sat in {@code index.html} as literal text, which would go on saying
 * 10 and 20MB after either limit was reconfigured.
 *
 * <p>These are limits on the <em>shape of the request</em>, not verdicts on a
 * file. A client may check them before sending; whether a file is allowed
 * remains the server's decision alone.
 *
 * @param maxFilesPerRequest files accepted in one multipart request
 * @param maxFileSizeBytes   ceiling for a single file, in bytes
 */
public record UploadLimitsDto(int maxFilesPerRequest, long maxFileSizeBytes) {

    public static UploadLimitsDto from(StorageProperties properties) {
        return new UploadLimitsDto(
                properties.getMaxFilesPerRequest(),
                properties.getMaxFileSize().toBytes());
    }
}
