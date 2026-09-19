package com.flow.extguard.upload.validation;

import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.common.ApiException;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.config.StorageProperties;
import com.flow.extguard.policy.domain.FixedExtensions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Applies the upload rules in a fixed order and returns a verdict.
 *
 * <p>The order matters for the quality of the message the user sees. When a file
 * would fail several rules at once, the configured policy is reported first: if
 * the administrator blocked {@code exe}, the user should be told that, not that
 * the bytes look like a PE binary.
 *
 * <pre>
 *   R1 empty file
 *   R2 oversized
 *   R3 malformed filename
 *   R4 extension chain intersects the blocklist   <- the configured policy
 *   R5 executable content that the extension does not honestly declare
 *   R6 content contradicts the extension
 *   R7 declared MIME contradicts content          <- logged, never rejected
 * </pre>
 *
 * <p><strong>R5 detects disguise, not executables.</strong> An earlier version
 * rejected any executable content outright, which quietly made the fixed
 * extension checkboxes meaningless: unchecking {@code exe} still could not let a
 * real {@code .exe} through, because R5 refused it on content alone. The
 * management screen promised something the upload path did not honour.
 *
 * <p>So a file whose extension honestly declares what it contains is left to the
 * configured policy -- R4 has already decided whether that extension is allowed.
 * R5 now fires only when the name hides the content ({@code report.jpg} holding a
 * PE binary) or declares nothing at all ({@code payload} with no extension, which
 * an extension policy cannot govern).
 */
@Component
public class UploadValidator {

    private static final Logger log = LoggerFactory.getLogger(UploadValidator.class);

    /**
     * Extensions whose content is unambiguous enough to cross-check. Formats
     * without a stable single signature (docx, txt, csv...) are deliberately absent:
     * a rule that cannot be evaluated reliably would only produce false rejections.
     */
    private static final Map<String, Set<String>> EXPECTED_SIGNATURES = Map.of(
            "png", Set.of("PNG"),
            "jpg", Set.of("JPEG"),
            "jpeg", Set.of("JPEG"),
            "gif", Set.of("GIF"),
            "bmp", Set.of("BMP"),
            "pdf", Set.of("PDF"),
            "zip", Set.of("ZIP", "ZIP_EMPTY"),
            "gz", Set.of("GZIP"));

    /** Extensions for which an embedded script marker means a polyglot webshell. */
    private static final Set<String> IMAGE_LIKE =
            Set.of("png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "svg");

    /**
     * Extensions that honestly declare each executable or script signature.
     *
     * <p>A file carrying one of these extensions is not in disguise: it says what
     * it is, so whether it may be uploaded is the extension policy's decision
     * rather than this rule's. Anything else carrying executable content is
     * hiding, and R5 rejects it.
     */
    private static final Map<String, Set<String>> DECLARING_EXTENSIONS = Map.of(
            "PE_EXE", Set.of("exe", "dll", "scr", "com", "sys", "msi", "ocx", "cpl", "drv", "efi"),
            "ELF", Set.of("so", "o", "elf", "bin", "out", "ko"),
            "MACH_O_32", Set.of("dylib", "bundle", "o"),
            "MACH_O_64", Set.of("dylib", "bundle", "o"),
            "MACH_O_LE32", Set.of("dylib", "bundle", "o"),
            "MACH_O_LE64", Set.of("dylib", "bundle", "o"),
            // CAFEBABE is both a Java class file and a Mach-O fat binary.
            "JAVA_CLASS", Set.of("class", "dylib"),
            "SHEBANG", Set.of("sh", "bash", "zsh", "ksh", "csh", "py", "pl", "rb", "php",
                    "lua", "awk", "cgi"));

    private final PolicyProperties policyProperties;
    private final StorageProperties storageProperties;
    private final FilenameAnalyzer filenameAnalyzer;
    private final ContentSignatureDetector signatureDetector;

    public UploadValidator(PolicyProperties policyProperties,
                           StorageProperties storageProperties,
                           FilenameAnalyzer filenameAnalyzer,
                           ContentSignatureDetector signatureDetector) {
        this.policyProperties = policyProperties;
        this.storageProperties = storageProperties;
        this.filenameAnalyzer = filenameAnalyzer;
        this.signatureDetector = signatureDetector;
    }

    /**
     * @param blockedExtensions the effective blocklist, already normalised
     */
    public UploadVerdict validate(UploadCandidate candidate, Set<String> blockedExtensions) {
        // R1 -- an empty file carries no content to judge and is never useful.
        if (candidate.sizeBytes() <= 0) {
            return UploadVerdict.reject(ApiErrorCode.EMPTY_FILE,
                    ApiErrorCode.EMPTY_FILE.message(),
                    "파일 크기가 0바이트입니다.", null, null);
        }

        // R2 -- the servlet container normally rejects this earlier; repeated here
        // so the limit still holds if the container config is ever relaxed.
        long maxBytes = storageProperties.getMaxFileSize().toBytes();
        if (candidate.sizeBytes() > maxBytes) {
            return UploadVerdict.reject(ApiErrorCode.FILE_TOO_LARGE,
                    ApiErrorCode.FILE_TOO_LARGE.message(storageProperties.getMaxFileSize().toMegabytes() + "MB"),
                    "파일 크기 " + candidate.sizeBytes() + "바이트 (허용 " + maxBytes + "바이트)", null, null);
        }

        // R3 -- structural filename problems.
        FilenameAnalysis analysis;
        try {
            analysis = filenameAnalyzer.analyze(candidate.filename());
        } catch (ApiException e) {
            return UploadVerdict.reject(e.code(), e.getMessage(), e.detail(), null, null);
        }

        FileSignature signature = signatureDetector.detect(candidate.header()).orElse(null);

        // R4 -- the configured policy. Checked before content so the user hears
        // about the rule an administrator actually set.
        Optional<String> blockedSegment = firstBlockedSegment(analysis, blockedExtensions);
        if (blockedSegment.isPresent()) {
            String segment = blockedSegment.get();
            String origin = FixedExtensions.contains(segment) ? "고정" : "커스텀";
            String detail = "파일명 '%s'의 확장자 체인 [%s] 중 '%s'가 %s 차단 목록에 있습니다."
                    .formatted(analysis.displayFilename(), analysis.chainAsString(), segment, origin);
            return UploadVerdict.reject(ApiErrorCode.EXTENSION_BLOCKED,
                    ApiErrorCode.EXTENSION_BLOCKED.message(segment), detail, analysis, signature);
        }

        Optional<String> extension = analysis.effectiveExtension();

        // R5 -- executable content the name does not own up to.
        //
        // Reaching here means R4 allowed the extension, so an honestly named
        // executable is one the administrator has chosen to permit. Only disguise
        // is rejected.
        if (signature != null && signature.isExecutableOrScript()
                && !declaresItsOwnContent(extension, signature)) {
            String detail = extension.isEmpty()
                    ? "확장자가 없는 파일의 내용이 %s로 확인되었습니다. 확장자가 없으면 차단 정책을 적용할 수 없어 거부합니다."
                            .formatted(signature.id())
                    : "파일명은 '%s'이지만 실제 내용의 시그니처가 %s로, '%s' 확장자와 일치하지 않습니다."
                            .formatted(analysis.displayFilename(), signature.id(), extension.get());
            return UploadVerdict.reject(ApiErrorCode.EXECUTABLE_CONTENT,
                    ApiErrorCode.EXECUTABLE_CONTENT.message(), detail, analysis, signature);
        }

        // R5b -- polyglot: an "image" carrying server-side script markers.
        if (extension.isPresent()
                && IMAGE_LIKE.contains(extension.get())
                && signatureDetector.containsScriptMarkers(candidate.header())) {
            String detail = "이미지 확장자('%s')이지만 내용에 스크립트 코드가 포함되어 있습니다."
                    .formatted(extension.get());
            return UploadVerdict.reject(ApiErrorCode.EXECUTABLE_CONTENT,
                    ApiErrorCode.EXECUTABLE_CONTENT.message(), detail, analysis, signature);
        }

        // R6 -- content contradicts the extension, for formats we can check confidently.
        if (extension.isPresent()) {
            Set<String> expected = EXPECTED_SIGNATURES.get(extension.get());
            if (expected != null && (signature == null || !expected.contains(signature.id()))) {
                String actual = signature == null ? "알 수 없음" : signature.id();
                String detail = "확장자 '%s'는 %s 시그니처를 기대하지만 실제 내용은 %s입니다."
                        .formatted(extension.get(), String.join("/", expected), actual);
                return UploadVerdict.reject(ApiErrorCode.CONTENT_TYPE_MISMATCH,
                        ApiErrorCode.CONTENT_TYPE_MISMATCH.message(extension.get()), detail, analysis, signature);
            }
        }

        // R7 -- the browser's declared MIME type is recorded and logged when it
        // disagrees with the content, but never drives a rejection. It is
        // attacker-controlled and routinely wrong for legitimate files, so
        // refusing on it would add false positives without adding security;
        // R5 and R6 already cover the real threat.
        logDeclaredTypeMismatch(candidate, signature);

        return UploadVerdict.accept(analysis, signature);
    }

    /**
     * @return true when the extension is one that legitimately carries this
     *         signature, so the file is named honestly rather than disguised.
     *         A file with no extension declares nothing and never qualifies.
     */
    private static boolean declaresItsOwnContent(Optional<String> extension, FileSignature signature) {
        return extension
                .map(value -> DECLARING_EXTENSIONS.getOrDefault(signature.id(), Set.of()).contains(value))
                .orElse(false);
    }

    private Optional<String> firstBlockedSegment(FilenameAnalysis analysis, Set<String> blockedExtensions) {
        List<String> chain = analysis.extensionChain();
        if (chain.isEmpty()) {
            return Optional.empty();
        }
        // Checking every segment closes the double-extension bypass
        // ("invoice.pdf.exe"). Narrowing to the last segment is available as a
        // config switch for teams that hit false positives on names like
        // "backup.exe.log"; see docs/01-decisions.md.
        List<String> candidates = policyProperties.isCheckFullExtensionChain()
                ? chain
                : List.of(chain.get(chain.size() - 1));

        return candidates.stream().filter(blockedExtensions::contains).findFirst();
    }

    private void logDeclaredTypeMismatch(UploadCandidate candidate, FileSignature signature) {
        if (signature == null || candidate.declaredContentType() == null) {
            return;
        }
        String declared = candidate.declaredContentType().toLowerCase(java.util.Locale.ROOT);
        boolean declaredImage = declared.startsWith("image/");
        if (declaredImage && signature.family() != SignatureFamily.IMAGE) {
            log.info("Declared content type '{}' disagrees with detected signature {} for '{}' "
                            + "(recorded only; MIME is not used as a rejection criterion)",
                    candidate.declaredContentType(), signature.id(), candidate.filename());
        }
    }
}
