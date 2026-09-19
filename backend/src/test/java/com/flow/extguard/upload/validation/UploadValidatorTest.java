package com.flow.extguard.upload.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.config.StorageProperties;
import com.flow.extguard.policy.service.ExtensionNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UploadValidatorTest {

    private final PolicyProperties policyProperties = new PolicyProperties();
    private final StorageProperties storageProperties = new StorageProperties();
    private final UploadValidator validator = new UploadValidator(
            policyProperties,
            storageProperties,
            new FilenameAnalyzer(new ExtensionNormalizer(policyProperties)),
            new ContentSignatureDetector());

    private static final byte[] TEXT = "hello, world".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PE_HEADER = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00};
    private static final byte[] PNG_HEADER =
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private UploadCandidate candidate(String filename, byte[] header) {
        return new UploadCandidate(filename, header.length, "application/octet-stream", header);
    }

    // --- R1 / R2 -------------------------------------------------------------

    @Test
    void rejectsEmptyFile() {
        var verdict = validator.validate(
                new UploadCandidate("notes.txt", 0, "text/plain", new byte[0]), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EMPTY_FILE);
    }

    @Test
    void rejectsOversizedFile() {
        long tooBig = storageProperties.getMaxFileSize().toBytes() + 1;
        var verdict = validator.validate(
                new UploadCandidate("big.txt", tooBig, "text/plain", TEXT), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.FILE_TOO_LARGE);
    }

    // --- R4: the configured policy -------------------------------------------

    @Test
    @DisplayName("accepts a file whose extension is not blocked")
    void acceptsUnblockedFile() {
        var verdict = validator.validate(candidate("notes.txt", TEXT), Set.of("exe", "bat"));

        assertThat(verdict.accepted()).isTrue();
        assertThat(verdict.analysis().effectiveExtension()).contains("txt");
    }

    @Test
    @DisplayName("rejects a blocked extension and names it in the message")
    void rejectsBlockedExtension() {
        var verdict = validator.validate(candidate("setup.bat", TEXT), Set.of("bat"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
        assertThat(verdict.message()).contains("bat");
        assertThat(verdict.detail()).contains("setup.bat").contains("고정");
    }

    @Test
    @DisplayName("blocks on any segment of the chain, closing the double-extension bypass")
    void rejectsBlockedSegmentAnywhereInChain() {
        var verdict = validator.validate(candidate("invoice.pdf.exe", TEXT), Set.of("exe"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
        assertThat(verdict.detail()).contains("pdf,exe").contains("'exe'");
    }

    @Test
    void rejectsBlockedSegmentInTheMiddleOfTheChain() {
        var verdict = validator.validate(candidate("payload.exe.txt", TEXT), Set.of("exe"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
    }

    @Test
    @DisplayName("matches case-insensitively, so .EXE is blocked like .exe")
    void blocksRegardlessOfCase() {
        var verdict = validator.validate(candidate("Setup.EXE", TEXT), Set.of("exe"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
    }

    @Test
    @DisplayName("blocks a dotfile whose suffix is on the list")
    void blocksDotfile() {
        var verdict = validator.validate(candidate(".env", TEXT), Set.of("env"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
    }

    @Test
    void reportsCustomOriginForANonFixedExtension() {
        var verdict = validator.validate(candidate("script.sh", TEXT), Set.of("sh"));

        assertThat(verdict.detail()).contains("커스텀");
    }

    @Test
    @DisplayName("checks only the last segment when the chain switch is off")
    void honoursTheChainConfigurationSwitch() {
        policyProperties.setCheckFullExtensionChain(false);
        try {
            var verdict = validator.validate(candidate("backup.exe.log", TEXT), Set.of("exe"));
            assertThat(verdict.accepted()).isTrue();
        } finally {
            policyProperties.setCheckFullExtensionChain(true);
        }
    }

    // --- R5: content over name ----------------------------------------------

    /** The headline case: a file named like an image whose bytes are a PE binary. */
    @Test
    @DisplayName("rejects an executable disguised as a jpg")
    void rejectsExecutableDisguisedAsImage() {
        var verdict = validator.validate(candidate("report.jpg", PE_HEADER), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
        assertThat(verdict.detail()).contains("PE_EXE");
    }

    @Test
    @DisplayName("rejects script content hiding under a .txt name")
    void rejectsShellScriptDisguisedAsText() {
        var verdict = validator.validate(
                candidate("notes.txt", "#!/bin/bash\nrm -rf /\n".getBytes(StandardCharsets.UTF_8)),
                Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    /**
     * The case the original rule got wrong, and that no test covered because every
     * ".exe" fixture held text rather than real PE bytes.
     *
     * <p>A genuine executable named .exe is not in disguise. With the exe checkbox
     * unchecked the administrator has allowed it, and the upload must honour that
     * -- otherwise the checkbox means nothing.
     */
    @Test
    @DisplayName("accepts a real executable that is honestly named .exe when exe is unblocked")
    void acceptsHonestlyNamedExecutableWhenPolicyAllowsIt() {
        var verdict = validator.validate(candidate("setup.exe", PE_HEADER), Set.of());

        assertThat(verdict.accepted())
                .as("unchecking exe must actually allow a real exe")
                .isTrue();
    }

    @Test
    @DisplayName("blocks the same real executable once exe is checked")
    void blocksHonestlyNamedExecutableWhenPolicyForbidsIt() {
        var verdict = validator.validate(candidate("setup.exe", PE_HEADER), Set.of("exe"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
    }

    @Test
    @DisplayName("accepts a real shell script named .sh when sh is unblocked")
    void acceptsHonestlyNamedScript() {
        var verdict = validator.validate(
                candidate("deploy.sh", "#!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8)),
                Set.of());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    void blocksHonestlyNamedScriptWhenPolicyForbidsIt() {
        var verdict = validator.validate(
                candidate("deploy.sh", "#!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8)),
                Set.of("sh"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
    }

    /**
     * Without an extension the blocking policy has nothing to act on, so allowing
     * this would let any executable through regardless of what is configured.
     */
    @Test
    @DisplayName("rejects an executable with no extension at all")
    void rejectsExecutableWithoutExtension() {
        var verdict = validator.validate(candidate("payload", PE_HEADER), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
        assertThat(verdict.detail()).contains("확장자가 없");
    }

    @Test
    @DisplayName("an unrelated executable extension does not count as honest")
    void rejectsExecutableUnderAMismatchedExecutableExtension() {
        // ELF content under a Windows executable extension is still a disguise.
        byte[] elf = new byte[]{0x7F, 0x45, 0x4C, 0x46, 0x02};
        var verdict = validator.validate(candidate("setup.exe", elf), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    @Test
    @DisplayName("rejects a polyglot webshell hiding php inside an image name")
    void rejectsPolyglotWebshell() {
        byte[] payload = "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8);
        var verdict = validator.validate(candidate("avatar.png", payload), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    /**
     * Rule order is deliberate: when both the policy and the content would reject
     * a file, the user is told about the policy the administrator configured.
     */
    @Test
    @DisplayName("reports the blocked extension ahead of the executable content")
    void extensionPolicyTakesPrecedenceOverContentRule() {
        var verdict = validator.validate(candidate("virus.exe", PE_HEADER), Set.of("exe"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
    }

    // --- R6: content contradicts the extension -------------------------------

    @Test
    void rejectsPngExtensionWithNonPngContent() {
        var verdict = validator.validate(candidate("photo.png", TEXT), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.CONTENT_TYPE_MISMATCH);
    }

    @Test
    void acceptsPngExtensionWithRealPngContent() {
        var verdict = validator.validate(candidate("photo.png", PNG_HEADER), Set.of());

        assertThat(verdict.accepted()).isTrue();
    }

    @Test
    @DisplayName("does not cross-check formats without a single stable signature")
    void acceptsTextFileWithoutSignature() {
        assertThat(validator.validate(candidate("notes.txt", TEXT), Set.of()).accepted()).isTrue();
        assertThat(validator.validate(candidate("data.csv", TEXT), Set.of()).accepted()).isTrue();
    }

    // --- R7: MIME is recorded, never decisive --------------------------------

    @Test
    @DisplayName("accepts despite a lying Content-Type, because MIME is never trusted")
    void declaredMimeTypeDoesNotCauseRejection() {
        var verdict = validator.validate(
                new UploadCandidate("photo.png", PNG_HEADER.length, "application/x-msdownload", PNG_HEADER),
                Set.of());

        assertThat(verdict.accepted())
                .as("the bytes really are a PNG; the browser's claim is irrelevant")
                .isTrue();
    }

    // --- filenames -----------------------------------------------------------

    @Test
    void acceptsFileWithoutExtensionWhenNothingMatches() {
        assertThat(validator.validate(candidate("README", TEXT), Set.of("exe")).accepted()).isTrue();
    }

    @Test
    void rejectsMalformedFilename() {
        var verdict = validator.validate(candidate("bad\nname.txt", TEXT), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.FILENAME_CONTROL_CHAR);
    }

    @Test
    @DisplayName("still blocks after trailing dots are stripped")
    void blocksTrailingDotEvasion() {
        var verdict = validator.validate(candidate("evil.exe.", TEXT), Set.of("exe"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
    }
}
