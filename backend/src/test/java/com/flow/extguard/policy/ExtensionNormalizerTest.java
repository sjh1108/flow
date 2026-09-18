package com.flow.extguard.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.common.ApiException;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.policy.service.ExtensionNormalizer;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExtensionNormalizerTest {

    private final ExtensionNormalizer normalizer = new ExtensionNormalizer(new PolicyProperties());

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource({
            "exe,       exe",
            "EXE,       exe",
            "'.exe',    exe",
            "'..exe',   exe",
            "'  sh  ',  sh",
            "'.TAR',    tar",
            "Sh,        sh",
            "mp4,       mp4",
            "'7z',      7z",
    })
    @DisplayName("normalises case, surrounding space and leading dots")
    void normalisesToCanonicalForm(String raw, String expected) {
        assertThat(normalizer.normalize(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("folds fullwidth characters so lookalikes cannot bypass the blocklist")
    void foldsFullwidthCharacters() {
        // U+FF25 U+FF38 U+FF25 -- renders as "ＥＸＥ" but is not ASCII "EXE".
        assertThat(normalizer.normalize("ＥＸＥ")).isEqualTo("exe");
    }

    /**
     * The reason {@code toLowerCase(Locale.ROOT)} is spelled out in the normalizer.
     * Under a Turkish default locale, {@code "TIF".toLowerCase()} produces "tıf"
     * with a dotless i, which would no longer match a stored "tif" -- a silent
     * hole in the blocklist that depends on the server's locale.
     */
    @Test
    @DisplayName("normalises identically under a Turkish default locale")
    void normalizesConsistentlyUnderTurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat("TIF".toLowerCase())
                    .as("precondition: the JDK really does fold I to a dotless i here")
                    .isEqualTo("tıf");

            assertThat(normalizer.normalize("TIF")).isEqualTo("tif");
            assertThat(normalizer.normalize("INI")).isEqualTo("ini");
        } finally {
            Locale.setDefault(original);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "."})
    @DisplayName("rejects input that normalises to nothing")
    void rejectsEmpty(String raw) {
        assertThatThrownBy(() -> normalizer.normalize(raw))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.EXT_EMPTY);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("rejects an embedded dot with a message naming the problem")
    void rejectsEmbeddedDot() {
        assertThatThrownBy(() -> normalizer.normalize("tar.gz"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.EXT_CONTAINS_DOT);
    }

    @Test
    void rejectsInternalWhitespace() {
        assertThatThrownBy(() -> normalizer.normalize("e xe"))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.EXT_CONTAINS_WHITESPACE);
    }

    @Test
    @DisplayName("enforces the 20 character limit on the normalised value")
    void rejectsOverlongExtension() {
        assertThat(normalizer.normalize("a".repeat(20))).hasSize(20);

        assertThatThrownBy(() -> normalizer.normalize("a".repeat(21)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ApiErrorCode.EXT_TOO_LONG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"한글", "ex-e", "ex_e", "ex/e", "ex\\e", "ex;e", "exe!", "ex*"})
    @DisplayName("rejects anything outside [a-z0-9]")
    void rejectsDisallowedCharacters(String raw) {
        assertThatThrownBy(() -> normalizer.normalize(raw))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("rejects a NUL byte rather than truncating at it")
    void rejectsNulByte() {
        assertThatThrownBy(() -> normalizer.normalize("exe\u0000"))
                .isInstanceOf(ApiException.class);
    }
}
