package com.flow.extguard.policy.service;

import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.common.ApiException;
import com.flow.extguard.config.PolicyProperties;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Turns whatever the user typed into the single canonical form the system stores
 * and compares against.
 *
 * <p>Everything downstream -- the blocklist, the UNIQUE index, the CHECK
 * constraints -- assumes extensions are {@code [a-z0-9]{1,20}}. This class is the
 * only place that assumption is established, so it is strict on purpose.
 */
@Component
public class ExtensionNormalizer {

    private static final Pattern ALLOWED = Pattern.compile("^[a-z0-9]+$");

    private final PolicyProperties policyProperties;

    public ExtensionNormalizer(PolicyProperties policyProperties) {
        this.policyProperties = policyProperties;
    }

    /**
     * @param raw user input, e.g. {@code ".EXE"}, {@code " sh "}, {@code "ＥＸＥ"}
     * @return the canonical form, e.g. {@code "exe"}, {@code "sh"}
     * @throws ApiException with a code naming the specific problem
     */
    public String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ApiErrorCode.EXT_EMPTY, "입력값이 비어 있습니다.");
        }

        // NFKC folds compatibility forms, so fullwidth "ＥＸＥ" and similar
        // lookalikes collapse to plain ASCII instead of sneaking past the
        // blocklist as a distinct string.
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKC).strip();

        // A leading dot is how people naturally write extensions (".exe"), so
        // accept it rather than erroring. Trailing dots are rejected below via
        // the dot check, since ".exe." is more likely a mistake than an intent.
        int firstNonDot = 0;
        while (firstNonDot < value.length() && value.charAt(firstNonDot) == '.') {
            firstNonDot++;
        }
        value = value.substring(firstNonDot).strip();

        if (value.isEmpty()) {
            throw new ApiException(ApiErrorCode.EXT_EMPTY, "점을 제외하면 남는 문자가 없습니다.");
        }

        // Locale.ROOT, never the default locale. Under a Turkish default locale
        // "EXE".toLowerCase() yields "exe" with a dotless i for words containing
        // I, so a blocklist comparison would silently stop matching. This is
        // pinned by ExtensionNormalizerTest#normalizesConsistentlyUnderTurkishLocale.
        value = value.toLowerCase(Locale.ROOT);

        // Specific diagnostics before the catch-all, so the user is told what to
        // fix rather than just "invalid".
        if (value.indexOf('.') >= 0) {
            throw new ApiException(ApiErrorCode.EXT_CONTAINS_DOT, "입력값: '" + raw + "'");
        }
        if (containsWhitespace(value)) {
            throw new ApiException(ApiErrorCode.EXT_CONTAINS_WHITESPACE, "입력값: '" + raw + "'");
        }

        int maxLength = policyProperties.getMaxExtensionLength();
        if (value.length() > maxLength) {
            throw new ApiException(ApiErrorCode.EXT_TOO_LONG,
                    "정규화 후 길이 " + value.length() + "자 (입력값: '" + raw + "')", maxLength);
        }
        if (!ALLOWED.matcher(value).matches()) {
            throw new ApiException(ApiErrorCode.EXT_INVALID_CHARSET, "입력값: '" + raw + "'");
        }

        return value;
    }

    /**
     * Lenient form used when analysing a filename that arrived with an upload.
     * A malformed segment there is not a user input error to report -- it simply
     * cannot match the blocklist -- so it is folded rather than rejected.
     */
    public String normalizeLeniently(String segment) {
        return Normalizer.normalize(segment, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean containsWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
