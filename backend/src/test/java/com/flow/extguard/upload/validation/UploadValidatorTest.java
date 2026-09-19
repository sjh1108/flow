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

    // These are magic-number prefixes, not valid files of their formats. The
    // detector matches leading bytes only, so a prefix is all these tests need --
    // but they should not be described as executables of those formats.
    private static final byte[] PE_HEADER = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00};
    private static final byte[] ELF_HEADER = new byte[]{0x7F, 0x45, 0x4C, 0x46, 0x02};
    private static final byte[] PNG_HEADER =
            new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] NODE_SHEBANG =
            "#!/usr/bin/env node\nconsole.log(1)\n".getBytes(StandardCharsets.UTF_8);

    /** 0xCAFEBABE shaped like a Java class (minor 0, major 52) -- but see below. */
    private static final byte[] JAVA_CLASS_HEADER = new byte[]{
            (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x00, 0x00, 0x34};

    /** 0xCAFEBABE shaped like a Mach-O fat binary (nfat_arch 2). */
    private static final byte[] MACH_O_FAT_HEADER = new byte[]{
            (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x00, 0x00, 0x02};

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
     * ".exe" fixture held text rather than the PE magic number.
     *
     * <p>A PE-signature fixture named .exe is not in disguise. With the exe
     * checkbox unchecked the administrator has allowed it, and the upload must
     * honour that -- otherwise the checkbox means nothing.
     */
    @Test
    @DisplayName("accepts a PE-signature fixture honestly named .exe when exe is unblocked")
    void acceptsHonestlyNamedExecutableWhenPolicyAllowsIt() {
        var verdict = validator.validate(candidate("setup.exe", PE_HEADER), Set.of());

        assertThat(verdict.accepted())
                .as("unchecking exe must allow PE content named .exe")
                .isTrue();
    }

    @Test
    @DisplayName("blocks the same PE-signature fixture once exe is checked")
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
        var verdict = validator.validate(candidate("setup.exe", ELF_HEADER), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    /**
     * An MSI is an OLE compound document, not a PE. Listing {@code msi} as a PE
     * extension let a PE binary named {@code installer.msi} read as honestly named
     * and be accepted whenever {@code msi} was not explicitly blocked.
     */
    @Test
    @DisplayName("rejects a PE binary disguised as an .msi installer")
    void rejectsPeExecutableDisguisedAsMsi() {
        var verdict = validator.validate(candidate("installer.msi", PE_HEADER), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    /**
     * {@code .bin} is a generic container extension. It declares nothing about the
     * format inside, so it cannot serve as an honest declaration of an ELF binary.
     */
    @Test
    @DisplayName("rejects an ELF binary under the generic .bin extension")
    void rejectsElfDisguisedAsGenericBin() {
        var verdict = validator.validate(candidate("payload.bin", ELF_HEADER), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    /**
     * {@code .out} is a filename convention rather than a format. A compiler
     * writes {@code a.out} when given no {@code -o}, but the same extension names
     * redirected output just as often, so it declares nothing about the content.
     */
    @Test
    @DisplayName("rejects an ELF binary under the conventional .out extension")
    void rejectsElfUnderConventionalOutExtension() {
        var verdict = validator.validate(candidate("results.out", ELF_HEADER), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    // --- js is a fixed extension, so its checkbox has to govern -------------

    @Test
    @DisplayName("accepts a shebang .js file when js is unblocked")
    void acceptsJavaScriptWithShebangWhenJsUnblocked() {
        var verdict = validator.validate(candidate("build.js", NODE_SHEBANG), Set.of());

        assertThat(verdict.accepted())
                .as("js is one of the seven fixed extensions; unchecking it must allow the file")
                .isTrue();
    }

    @Test
    void blocksJavaScriptWithShebangWhenJsChecked() {
        var verdict = validator.validate(candidate("build.js", NODE_SHEBANG), Set.of("js"));

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXTENSION_BLOCKED);
    }

    // --- 0xCAFEBABE: claimed by two formats, resolvable to neither -----------

    /**
     * A Java class file and a Mach-O fat binary read the same four bytes after the
     * magic number as different fields, and neither specification bounds its field
     * so as to exclude the other. Because the content cannot be pinned down, no
     * name can be shown to declare it honestly, so every 0xCAFEBABE file is
     * refused whatever it is called.
     */
    @Test
    @DisplayName("rejects class-shaped CAFEBABE bytes even when named .class")
    void rejectsJavaClassBytesEvenWhenNamedClass() {
        var verdict = validator.validate(candidate("Foo.class", JAVA_CLASS_HEADER), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
        assertThat(verdict.detail()).contains("확정할 수 없");
    }

    @Test
    @DisplayName("rejects fat-shaped CAFEBABE bytes even when named .dylib")
    void rejectsMachOFatBytesEvenWhenNamedDylib() {
        var verdict = validator.validate(candidate("lib.dylib", MACH_O_FAT_HEADER), Set.of());

        assertThat(verdict.rejected()).isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    @Test
    void rejectsCafebabeAcrossSwappedExtensions() {
        assertThat(validator.validate(candidate("malicious.dylib", JAVA_CLASS_HEADER), Set.of()).code())
                .isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
        assertThat(validator.validate(candidate("Foo.class", MACH_O_FAT_HEADER), Set.of()).code())
                .isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    /**
     * Pins the bypass that the earlier version-range guess allowed. It classified
     * bytes 6-7 below 45 as an architecture count and anything higher as a Java
     * version, so an attacker only had to choose {@code nfat_arch >= 45} to have a
     * Mach-O fat binary accepted as an honestly named {@code .class}.
     */
    @Test
    @DisplayName("a crafted nfat_arch of 52 cannot pose as a Java class")
    void craftedNfatArchCannotPoseAsJavaClass() {
        byte[] crafted = new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0x00, 0x00, 0x00, 0x34};

        var verdict = validator.validate(candidate("payload.class", crafted), Set.of());

        assertThat(verdict.rejected())
                .as("nfat_arch has no upper bound, so this value proves nothing")
                .isTrue();
        assertThat(verdict.code()).isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
    }

    @Test
    @DisplayName("rejects an unresolvable CAFEBABE under either extension")
    void rejectsAmbiguousCafebabeForBothExtensions() {
        byte[] ambiguous = new byte[]{
                (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE,
                (byte) 0xFF, (byte) 0xFF, 0x00, 0x00};

        assertThat(validator.validate(candidate("Foo.class", ambiguous), Set.of()).code())
                .isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
        assertThat(validator.validate(candidate("lib.dylib", ambiguous), Set.of()).code())
                .isEqualTo(ApiErrorCode.EXECUTABLE_CONTENT);
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
