package com.flow.extguard.upload.web.dto;

import com.flow.extguard.common.ApiErrorCode;
import java.util.List;
import org.springframework.http.HttpStatus;

public record UploadResponse(int acceptedCount, int rejectedCount, List<UploadResultDto> results) {

    public static UploadResponse of(List<UploadResultDto> results) {
        int accepted = (int) results.stream().filter(r -> "ACCEPTED".equals(r.status())).count();
        return new UploadResponse(accepted, results.size() - accepted, results);
    }

    public boolean allRejected() {
        return acceptedCount == 0 && rejectedCount > 0;
    }

    /**
     * The status for a batch, from what happened to the files in it.
     *
     * <p>Anything accepted makes the request a success and the per-file verdicts
     * carry the rest. When everything was refused the status says why in the
     * broad sense: 422 means the files themselves were unacceptable and resending
     * them will not help, while 507 means the server had nowhere to put them and
     * the same files may well succeed later. Collapsing both into 422 would tell
     * a client to give up on a file that was never the problem.
     */
    public HttpStatus status() {
        if (!allRejected()) {
            return HttpStatus.OK;
        }
        boolean everyRejectionIsCapacity = results.stream()
                .allMatch(r -> ApiErrorCode.STORAGE_QUOTA_EXCEEDED.name().equals(r.code()));
        return everyRejectionIsCapacity
                ? HttpStatus.INSUFFICIENT_STORAGE
                : HttpStatus.UNPROCESSABLE_CONTENT;
    }
}
