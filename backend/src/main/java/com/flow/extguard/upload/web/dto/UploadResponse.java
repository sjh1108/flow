package com.flow.extguard.upload.web.dto;

import java.util.List;

public record UploadResponse(int acceptedCount, int rejectedCount, List<UploadResultDto> results) {

    public static UploadResponse of(List<UploadResultDto> results) {
        int accepted = (int) results.stream().filter(r -> "ACCEPTED".equals(r.status())).count();
        return new UploadResponse(accepted, results.size() - accepted, results);
    }

    public boolean allRejected() {
        return acceptedCount == 0 && rejectedCount > 0;
    }
}
