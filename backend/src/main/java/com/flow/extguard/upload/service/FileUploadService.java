package com.flow.extguard.upload.service;

import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.common.ApiException;
import com.flow.extguard.config.StorageProperties;
import com.flow.extguard.policy.service.ExtensionPolicyService;
import com.flow.extguard.upload.domain.UploadRecord;
import com.flow.extguard.upload.domain.UploadStatus;
import com.flow.extguard.upload.repository.UploadRecordRepository;
import com.flow.extguard.upload.validation.ContentSignatureDetector;
import com.flow.extguard.upload.validation.FilenameAnalysis;
import com.flow.extguard.upload.validation.UploadCandidate;
import com.flow.extguard.upload.validation.UploadValidator;
import com.flow.extguard.upload.validation.UploadVerdict;
import com.flow.extguard.upload.web.dto.UploadRecordDto;
import com.flow.extguard.upload.web.dto.UploadResponse;
import com.flow.extguard.upload.web.dto.UploadResultDto;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Enforces the extension policy on real uploads.
 *
 * <p>This is the half of the system that makes the management screen mean
 * something: the blocklist is read from the database on every request, so a
 * checkbox toggled a second ago applies to the very next upload.
 */
@Service
public class FileUploadService {

    private static final Logger log = LoggerFactory.getLogger(FileUploadService.class);

    private final ExtensionPolicyService policyService;
    private final UploadValidator validator;
    private final FileStorage storage;
    private final UploadRecordRepository recordRepository;
    private final StorageProperties storageProperties;

    public FileUploadService(ExtensionPolicyService policyService,
                             UploadValidator validator,
                             FileStorage storage,
                             UploadRecordRepository recordRepository,
                             StorageProperties storageProperties) {
        this.policyService = policyService;
        this.validator = validator;
        this.storage = storage;
        this.recordRepository = recordRepository;
        this.storageProperties = storageProperties;
    }

    public UploadResponse upload(List<MultipartFile> files, String clientIp) {
        if (files == null || files.isEmpty()) {
            throw new ApiException(ApiErrorCode.NO_FILE_SUBMITTED, "multipart 파트가 비어 있습니다.");
        }
        if (files.size() > storageProperties.getMaxFilesPerRequest()) {
            throw new ApiException(ApiErrorCode.TOO_MANY_FILES,
                    "요청에 포함된 파일 수: " + files.size(),
                    storageProperties.getMaxFilesPerRequest());
        }

        // Read once per request rather than per file: consistent within the batch,
        // and still fresh enough that a policy change takes effect immediately.
        Set<String> blockedExtensions = policyService.blockedExtensions();

        // Likewise measured once and then decremented locally, so ten files in
        // one request cannot each spend the same remaining headroom.
        StorageBudget budget = StorageBudget.of(
                recordRepository.sumLiveBytes(), storage.usableSpaceBytes(), storageProperties);

        List<UploadResultDto> results = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            results.add(process(file, blockedExtensions, budget, clientIp));
        }
        return UploadResponse.of(results);
    }

    private UploadResultDto process(MultipartFile file, Set<String> blockedExtensions,
                                    StorageBudget budget, String clientIp) {
        String filename = file.getOriginalFilename();

        byte[] header;
        try {
            header = readHeader(file);
        } catch (IOException e) {
            log.error("Could not read upload stream for '{}'", filename, e);
            throw new ApiException(ApiErrorCode.STORAGE_FAILURE, "업로드 스트림을 읽을 수 없습니다.");
        }

        UploadCandidate candidate = new UploadCandidate(
                filename, file.getSize(), file.getContentType(), header);
        UploadVerdict verdict = validator.validate(candidate, blockedExtensions);

        if (verdict.rejected()) {
            // Nothing is written to disk for a rejection -- only the record of it.
            UploadRecord saved = recordRepository.save(baseRecord(filename, verdict, candidate, clientIp)
                    .status(UploadStatus.REJECTED)
                    .rejectionCode(verdict.code().name())
                    .rejectionDetail(verdict.detail())
                    .sizeBytes(file.getSize())
                    .build());

            log.info("Rejected upload '{}' ({}): {}", filename, verdict.code(), verdict.detail());

            return new UploadResultDto(
                    displayName(verdict, filename), "REJECTED", verdict.code().name(),
                    verdict.message(), verdict.detail(), saved.getId(),
                    file.getSize(), null, signatureId(verdict));
        }

        // The file is acceptable; the question now is whether there is room for
        // it. Checked before the write rather than after a failed one, so a full
        // store is a clean refusal with a reason instead of a broken write.
        Optional<String> shortfall = budget.shortfall(file.getSize());
        if (shortfall.isPresent()) {
            UploadRecord saved = recordRepository.save(baseRecord(filename, verdict, candidate, clientIp)
                    .status(UploadStatus.REJECTED)
                    .rejectionCode(ApiErrorCode.STORAGE_QUOTA_EXCEEDED.name())
                    .rejectionDetail(shortfall.get())
                    .sizeBytes(file.getSize())
                    .build());

            log.warn("Refused upload '{}' for capacity: {}", filename, shortfall.get());

            return new UploadResultDto(
                    displayName(verdict, filename), "REJECTED",
                    ApiErrorCode.STORAGE_QUOTA_EXCEEDED.name(),
                    ApiErrorCode.STORAGE_QUOTA_EXCEEDED.message(), shortfall.get(), saved.getId(),
                    file.getSize(), null, signatureId(verdict));
        }

        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = storage.store(content);
        } catch (IOException e) {
            log.error("Failed to store '{}'", filename, e);
            throw new ApiException(ApiErrorCode.STORAGE_FAILURE, "디스크 쓰기에 실패했습니다.");
        }

        // File first, then the row. If the row fails the file is removed, so the
        // only possible inconsistency is an orphaned file -- never a row pointing
        // at a file that is not there.
        UploadRecord saved;
        try {
            saved = recordRepository.save(baseRecord(filename, verdict, candidate, clientIp)
                    .status(UploadStatus.ACCEPTED)
                    .storedName(stored.storedName())
                    .sizeBytes(stored.sizeBytes())
                    .sha256(stored.sha256())
                    .build());
        } catch (RuntimeException e) {
            storage.delete(stored.storedName());
            log.error("Rolled back stored file '{}' after the database write failed",
                    stored.storedName(), e);
            throw new ApiException(ApiErrorCode.STORAGE_FAILURE, "업로드 기록 저장에 실패했습니다.");
        }

        budget.charge(stored.sizeBytes());
        log.info("Accepted upload '{}' as {} ({} bytes)", filename, stored.storedName(), stored.sizeBytes());

        return new UploadResultDto(
                displayName(verdict, filename), "ACCEPTED", null, null, null,
                saved.getId(), stored.sizeBytes(), stored.sha256(), signatureId(verdict));
    }

    public List<UploadRecordDto> recentUploads(int limit) {
        return recordRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.clamp(limit, 1, 200)))
                .stream()
                .map(UploadRecordDto::from)
                .toList();
    }

    private UploadRecord.Builder baseRecord(String filename,
                                            UploadVerdict verdict,
                                            UploadCandidate candidate,
                                            String clientIp) {
        FilenameAnalysis analysis = verdict.analysis();
        return UploadRecord.builder()
                .originalFilename(filename == null ? "(unnamed)" : filename)
                .displayFilename(displayName(verdict, filename))
                .extensionChain(analysis == null ? null : analysis.chainAsString())
                .effectiveExtension(analysis == null
                        ? null
                        : analysis.effectiveExtension().orElse(null))
                .declaredContentType(candidate.declaredContentType())
                .detectedSignature(signatureId(verdict))
                .clientIp(clientIp)
                .createdAt(Instant.now());
    }

    private static String displayName(UploadVerdict verdict, String fallback) {
        if (verdict.analysis() != null) {
            return verdict.analysis().displayFilename();
        }
        return fallback == null ? "(unnamed)" : fallback;
    }

    private static String signatureId(UploadVerdict verdict) {
        return verdict.signature() == null ? null : verdict.signature().id();
    }

    /** Reads only the leading bytes needed for signature detection. */
    private static byte[] readHeader(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(ContentSignatureDetector.HEADER_BYTES);
        }
    }
}
