package com.flow.extguard.upload.validation;

import java.util.List;
import java.util.Optional;

/**
 * The structural reading of an uploaded filename.
 *
 * @param originalFilename what the client actually sent
 * @param displayFilename  sanitised for display: path components removed,
 *                         trailing dots and spaces stripped
 * @param baseName         the part before the first dot; empty for dotfiles
 * @param extensionChain   every extension segment, normalised, in order.
 *                         {@code invoice.pdf.exe} yields {@code [pdf, exe]}
 */
public record FilenameAnalysis(String originalFilename,
                               String displayFilename,
                               String baseName,
                               List<String> extensionChain) {

    /** The last segment, which is what most systems call "the extension". */
    public Optional<String> effectiveExtension() {
        return extensionChain.isEmpty()
                ? Optional.empty()
                : Optional.of(extensionChain.get(extensionChain.size() - 1));
    }

    public String chainAsString() {
        return String.join(",", extensionChain);
    }

    public boolean hasExtension() {
        return !extensionChain.isEmpty();
    }
}
