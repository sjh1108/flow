package com.flow.extguard.upload.validation;

import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.common.ApiException;
import com.flow.extguard.policy.service.ExtensionNormalizer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reads an uploaded filename structurally: strips anything path-like, rejects
 * names that are malformed or dangerous, and extracts the full extension chain.
 *
 * <p>Extracting the <em>chain</em> rather than just the final extension is what
 * closes the double-extension bypass. On Windows, {@code invoice.pdf.exe} displays
 * as {@code invoice.pdf} when known extensions are hidden, so checking only the
 * last segment would let the reverse trick through and checking only the first
 * would miss this one. Every segment is checked.
 */
@Component
public class FilenameAnalyzer {

    /** 255 bytes is the practical limit on ext4, APFS and NTFS alike. */
    private static final int MAX_FILENAME_BYTES = 255;

    /**
     * Windows device names. Reserved regardless of extension, so {@code CON.txt}
     * counts. Guarded even though this service runs on Linux: the stored files
     * or their names may later be synced to, or downloaded onto, Windows.
     */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private final ExtensionNormalizer extensionNormalizer;

    public FilenameAnalyzer(ExtensionNormalizer extensionNormalizer) {
        this.extensionNormalizer = extensionNormalizer;
    }

    public FilenameAnalysis analyze(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            throw new ApiException(ApiErrorCode.FILENAME_MISSING, "파일명이 비어 있습니다.");
        }

        String value = Normalizer.normalize(rawFilename, Normalizer.Form.NFKC);

        // Control characters can truncate names in logs and downstream consumers,
        // and a NUL byte can cut a name short in native code. Reject outright.
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new ApiException(ApiErrorCode.FILENAME_CONTROL_CHAR,
                        "파일명에 제어문자(U+%04X)가 포함되어 있습니다.".formatted((int) c));
            }
        }

        // Keep only the final path component. Some clients send a full path, and
        // "../../etc/passwd" must never survive as anything but "passwd".
        // Storage uses a generated UUID regardless, so this is defence in depth.
        int separator = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (separator >= 0) {
            value = value.substring(separator + 1);
        }

        // Windows silently drops trailing dots and spaces, which turns "evil.exe."
        // into "evil.exe" after the check would have passed. Strip them first so
        // the name we check is the name that ends up on disk elsewhere.
        value = stripTrailingDotsAndSpaces(value).strip();

        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            throw new ApiException(ApiErrorCode.FILENAME_INVALID, "정규화 후 남는 파일명이 없습니다: '" + rawFilename + "'");
        }

        int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength > MAX_FILENAME_BYTES) {
            throw new ApiException(ApiErrorCode.FILENAME_TOO_LONG,
                    "파일명 길이 " + byteLength + "바이트 (허용 " + MAX_FILENAME_BYTES + "바이트)");
        }

        String[] segments = value.split("\\.", -1);
        boolean dotfile = segments[0].isEmpty();
        String baseName = dotfile ? "" : segments[0];

        // Segment 0 is the base name, every later segment is an extension. For a
        // dotfile such as ".env" segment 0 is empty, so "env" lands in the chain
        // and is matched against the blocklist like any other extension.
        List<String> chain = new ArrayList<>();
        for (int i = 1; i < segments.length; i++) {
            if (!segments[i].isEmpty()) {
                chain.add(extensionNormalizer.normalizeLeniently(segments[i]));
            }
        }

        if (!baseName.isEmpty() && RESERVED_NAMES.contains(baseName.toUpperCase(Locale.ROOT))) {
            throw new ApiException(ApiErrorCode.FILENAME_RESERVED,
                    "예약어 '" + baseName + "' 사용", baseName);
        }

        return new FilenameAnalysis(rawFilename, value, baseName, List.copyOf(chain));
    }

    private static String stripTrailingDotsAndSpaces(String value) {
        int end = value.length();
        while (end > 0) {
            char c = value.charAt(end - 1);
            if (c == '.' || c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        return value.substring(0, end);
    }
}
