package com.flow.extguard.upload.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContentSignatureDetectorTest {

    private final ContentSignatureDetector detector = new ContentSignatureDetector();

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    @DisplayName("identifies a Windows executable by its MZ header")
    void detectsPeExecutable() {
        var signature = detector.detect(bytes(0x4D, 0x5A, 0x90, 0x00));

        assertThat(signature).isPresent();
        assertThat(signature.get().id()).isEqualTo("PE_EXE");
        assertThat(signature.get().family()).isEqualTo(SignatureFamily.EXECUTABLE);
        assertThat(signature.get().isExecutableOrScript()).isTrue();
    }

    @Test
    void detectsElfExecutable() {
        var signature = detector.detect(bytes(0x7F, 0x45, 0x4C, 0x46, 0x02));

        assertThat(signature).isPresent();
        assertThat(signature.get().family()).isEqualTo(SignatureFamily.EXECUTABLE);
    }

    // --- 0xCAFEBABE: claimed by two formats, resolvable to neither -----------

    /**
     * Java class files and Mach-O fat binaries share this magic number and read
     * the following four bytes as different fields, with neither specification
     * bounding its field so as to exclude the other. The detector therefore makes
     * no determination at all rather than guessing from the value.
     */
    @Test
    @DisplayName("reports every CAFEBABE variant as ambiguous")
    void reportsEveryCafebabeAsAmbiguous() {
        // class-shaped (minor 0, major 52)
        assertThat(detector.detect(bytes(0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x34)))
                .get().extracting(FileSignature::id)
                .isEqualTo(ContentSignatureDetector.AMBIGUOUS_CAFEBABE);
        // fat-shaped (nfat_arch 2)
        assertThat(detector.detect(bytes(0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x02)))
                .get().extracting(FileSignature::id)
                .isEqualTo(ContentSignatureDetector.AMBIGUOUS_CAFEBABE);
        // neither shape
        assertThat(detector.detect(bytes(0xCA, 0xFE, 0xBA, 0xBE, 0xFF, 0xFF, 0x00, 0x00)))
                .get().extracting(FileSignature::id)
                .isEqualTo(ContentSignatureDetector.AMBIGUOUS_CAFEBABE);
        // truncated
        assertThat(detector.detect(bytes(0xCA, 0xFE, 0xBA, 0xBE, 0x00)))
                .get().extracting(FileSignature::id)
                .isEqualTo(ContentSignatureDetector.AMBIGUOUS_CAFEBABE);
    }

    @Test
    @DisplayName("an ambiguous CAFEBABE is still executable content")
    void ambiguousCafebabeRemainsExecutable() {
        assertThat(detector.detect(bytes(0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, 0x00, 0x34)))
                .get().extracting(FileSignature::family).isEqualTo(SignatureFamily.EXECUTABLE);
    }

    @Test
    void detectsShebangScript() {
        var signature = detector.detect("#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8));

        assertThat(signature).isPresent();
        assertThat(signature.get().id()).isEqualTo("SHEBANG");
        assertThat(signature.get().isExecutableOrScript()).isTrue();
    }

    @Test
    void detectsPng() {
        var signature = detector.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A));

        assertThat(signature).isPresent();
        assertThat(signature.get().id()).isEqualTo("PNG");
        assertThat(signature.get().family()).isEqualTo(SignatureFamily.IMAGE);
        assertThat(signature.get().isExecutableOrScript()).isFalse();
    }

    @Test
    void detectsJpeg() {
        assertThat(detector.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
                .get()
                .extracting(FileSignature::id)
                .isEqualTo("JPEG");
    }

    @Test
    void detectsPdf() {
        assertThat(detector.detect("%PDF-1.7\n".getBytes(StandardCharsets.UTF_8)))
                .get()
                .extracting(FileSignature::id)
                .isEqualTo("PDF");
    }

    @Test
    @DisplayName("treats a zip container as an archive, covering jar and apk alike")
    void detectsZip() {
        assertThat(detector.detect(bytes(0x50, 0x4B, 0x03, 0x04)))
                .get()
                .extracting(FileSignature::family)
                .isEqualTo(SignatureFamily.ARCHIVE);
    }

    @Test
    void returnsEmptyForPlainText() {
        assertThat(detector.detect("hello, world".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void returnsEmptyForNullOrEmptyHeader() {
        assertThat(detector.detect(null)).isEmpty();
        assertThat(detector.detect(new byte[0])).isEmpty();
    }

    @Test
    void doesNotMatchWhenHeaderIsShorterThanTheSignature() {
        assertThat(detector.detect(bytes(0x7F, 0x45))).isEmpty();
    }

    @Test
    @DisplayName("spots server-side script markers used in polyglot webshells")
    void findsScriptMarkers() {
        assertThat(detector.containsScriptMarkers("<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(detector.containsScriptMarkers("<SCRIPT>alert(1)</SCRIPT>".getBytes(StandardCharsets.UTF_8)))
                .isTrue();
        assertThat(detector.containsScriptMarkers("just text".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }
}
