package com.flow.extguard.upload.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.common.ApiException;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.policy.service.ExtensionNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FilenameAnalyzerTest {

    private final FilenameAnalyzer analyzer =
            new FilenameAnalyzer(new ExtensionNormalizer(new PolicyProperties()));

    @Test
    @DisplayName("extracts every extension segment, not just the last")
    void extractsFullExtensionChain() {
        FilenameAnalysis analysis = analyzer.analyze("invoice.pdf.exe");

        assertThat(analysis.baseName()).isEqualTo("invoice");
        assertThat(analysis.extensionChain()).containsExactly("pdf", "exe");
        assertThat(analysis.effectiveExtension()).contains("exe");
    }

    @Test
    @DisplayName("handles the reverse double extension too")
    void handlesReverseDoubleExtension() {
        assertThat(analyzer.analyze("file.exe.txt").extensionChain())
                .containsExactly("exe", "txt");
    }

    @Test
    void handlesCompoundArchiveExtensions() {
        assertThat(analyzer.analyze("backup.tar.gz").extensionChain())
                .containsExactly("tar", "gz");
    }

    @Test
    @DisplayName("treats a dotfile's suffix as a checkable extension")
    void handlesDotfiles() {
        FilenameAnalysis analysis = analyzer.analyze(".env");

        assertThat(analysis.baseName()).isEmpty();
        assertThat(analysis.extensionChain()).containsExactly("env");
    }

    @Test
    void handlesFileWithNoExtension() {
        FilenameAnalysis analysis = analyzer.analyze("README");

        assertThat(analysis.baseName()).isEqualTo("README");
        assertThat(analysis.extensionChain()).isEmpty();
        assertThat(analysis.hasExtension()).isFalse();
    }

    @Test
    void lowercasesExtensionSegments() {
        assertThat(analyzer.analyze("Report.PDF").extensionChain()).containsExactly("pdf");
    }

    /**
     * Windows silently drops trailing dots, so "evil.exe." becomes "evil.exe" on
     * disk. Stripping them here means the name we check is the name that survives.
     */
    @Test
    @DisplayName("strips trailing dots so evil.exe. is still seen as exe")
    void stripsTrailingDots() {
        FilenameAnalysis analysis = analyzer.analyze("evil.exe.");

        assertThat(analysis.displayFilename()).isEqualTo("evil.exe");
        assertThat(analysis.extensionChain()).containsExactly("exe");
    }

    @Test
    void stripsTrailingSpaces() {
        assertThat(analyzer.analyze("evil.exe   ").extensionChain()).containsExactly("exe");
    }

    @Test
    void collapsesRepeatedDots() {
        assertThat(analyzer.analyze("weird..txt").extensionChain()).containsExactly("txt");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "../../etc/passwd",
            "/etc/passwd",
            "..\\..\\windows\\system32\\passwd",
            "C:\\temp\\passwd",
    })
    @DisplayName("keeps only the final path component")
    void stripsPathComponents(String raw) {
        assertThat(analyzer.analyze(raw).displayFilename()).isEqualTo("passwd");
    }

    @Test
    void stripsPathAndStillReadsTheExtension() {
        FilenameAnalysis analysis = analyzer.analyze("C:\\temp\\payload.exe");

        assertThat(analysis.displayFilename()).isEqualTo("payload.exe");
        assertThat(analysis.extensionChain()).containsExactly("exe");
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> analyzer.analyze("report\n.txt"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.FILENAME_CONTROL_CHAR);
    }

    @Test
    void rejectsNulByte() {
        assertThatThrownBy(() -> analyzer.analyze("report.txt\u0000.exe"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.FILENAME_CONTROL_CHAR);
    }

    @Test
    @DisplayName("enforces the 255 byte filename limit")
    void rejectsOverlongFilename() {
        assertThat(analyzer.analyze("a".repeat(251) + ".txt").displayFilename()).hasSize(255);

        assertThatThrownBy(() -> analyzer.analyze("a".repeat(252) + ".txt"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.FILENAME_TOO_LONG);
    }

    @Test
    @DisplayName("measures the filename limit in bytes, not characters")
    void measuresLengthInUtf8Bytes() {
        // Hangul is 3 bytes per character in UTF-8, so 90 characters is 270 bytes.
        assertThatThrownBy(() -> analyzer.analyze("가".repeat(90) + ".txt"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.FILENAME_TOO_LONG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CON", "CON.txt", "nul.log", "LPT1.dat", "com1"})
    @DisplayName("rejects Windows reserved device names")
    void rejectsReservedNames(String raw) {
        assertThatThrownBy(() -> analyzer.analyze(raw))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.FILENAME_RESERVED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ".", "..", "..."})
    void rejectsEmptyOrDotOnlyNames(String raw) {
        assertThatThrownBy(() -> analyzer.analyze(raw)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsNullFilename() {
        assertThatThrownBy(() -> analyzer.analyze(null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.FILENAME_MISSING);
    }
}
