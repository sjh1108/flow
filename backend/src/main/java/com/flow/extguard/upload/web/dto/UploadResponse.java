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
     * carry the rest. When everything was refused, 507 means every rejection was
     * for capacity -- the files are fine and the same request may well succeed
     * once space is reclaimed -- and 422 means at least one file was refused on
     * its own merits. Collapsing both into 422 would tell a client to give up on
     * a file that was never the problem.
     *
     * <p>A mixed batch is 422, and that status then describes the batch rather
     * than every file in it:
     *
     * <pre>
     * a.exe -&gt; EXTENSION_BLOCKED        (resending will not help)
     * b.txt -&gt; STORAGE_QUOTA_EXCEEDED   (resending may well work)
     * </pre>
     *
     * <p>507 would be the wrong answer there, because "try again later" is false
     * for {@code a.exe}: no amount of reclaimed space makes a blocked extension
     * acceptable. 422 is the safe summary -- it never promises a retry that
     * cannot succeed -- but it must not be read as "none of these can ever
     * succeed". Per-file retryability is in {@code results[].code}, which is the
     * only place it is ever exact, and a client that needs it reads there.
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
