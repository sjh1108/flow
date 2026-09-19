package com.flow.extguard.upload.web;

import com.flow.extguard.common.RequestActors;
import com.flow.extguard.upload.service.FileUploadService;
import com.flow.extguard.upload.web.dto.UploadRecordDto;
import com.flow.extguard.upload.web.dto.UploadResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * File upload, with the extension policy enforced server-side.
 *
 * <p>There is intentionally no download endpoint. Serving user-supplied files back
 * to a browser is the step that turns a stored file into a live threat, and it is
 * not needed to demonstrate that the policy is enforced.
 */
@RestController
@RequestMapping("/api/v1/files")
public class FileUploadController {

    private final FileUploadService uploadService;

    public FileUploadController(FileUploadService uploadService) {
        this.uploadService = uploadService;
    }

    /**
     * @return 200 when at least one file was accepted, 422 when every file was
     *         rejected. The body shape is identical either way, so the client
     *         renders per-file verdicts without branching on the status.
     */
    // 'files' is optional at the binding layer so that a request without the part
    // reaches the service and gets the proper NO_FILE_SUBMITTED message, rather
    // than failing binding and surfacing as a generic error.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            HttpServletRequest request) {
        UploadResponse response = uploadService.upload(files, RequestActors.clientIp(request));
        return ResponseEntity.status(response.status()).body(response);
    }

    @GetMapping
    public List<UploadRecordDto> recent(@RequestParam(defaultValue = "20") int limit) {
        return uploadService.recentUploads(limit);
    }
}
