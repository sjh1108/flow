package com.flow.extguard.upload.validation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Identifies what a file actually is by its leading bytes, independent of what
 * its name or declared MIME type claims.
 *
 * <p>This is the answer to "can the extension be trusted?" -- it cannot, so
 * {@code report.jpg} whose content starts with {@code MZ} is caught here rather
 * than by believing the name.
 *
 * <p>Only the first {@link #HEADER_BYTES} bytes are ever examined, so this stays
 * cheap and never pulls a whole upload into memory.
 *
 * <p><strong>This matches magic numbers; it does not validate file structure.</strong>
 * A file reported as {@code PE_EXE} starts with the bytes {@code MZ} -- it has not
 * been parsed, and its headers, sections and entry point are never checked. That is
 * the right trade for this purpose (the question is "what is this pretending to
 * be?", not "is this a well-formed binary?"), but it means a report of {@code PE_EXE}
 * must not be read as "this is a valid Windows executable".
 */
@Component
public class ContentSignatureDetector {

    public static final int HEADER_BYTES = 512;

    /**
     * Reported for 0xCAFEBABE, which two formats claim and neither can be ruled out.
     *
     * <p>A Java class file reads bytes 4-7 as {@code minor_version} then
     * {@code major_version}; a Mach-O fat binary reads the same four bytes as a
     * single {@code uint32_t nfat_arch}. <strong>Neither specification constrains
     * its field in a way that excludes the other</strong> -- Apple sets no upper
     * bound on {@code nfat_arch} -- so {@code CA FE BA BE 00 00 00 34} is a valid
     * reading of both (major 52, or 52 architectures).
     *
     * <p>An earlier version guessed between them by treating values below 45 as an
     * architecture count and the rest as a Java version. That was a guess dressed
     * up as a structural rule: an attacker simply picks {@code nfat_arch >= 45} and
     * the file is declared a Java class. Since this detector exists to resist
     * deliberate disguise, a field the attacker chooses freely is worthless as
     * evidence, so no determination is made at all.
     *
     * <p>Callers must never treat this id as evidence that a filename matches its
     * content -- an unresolved signature proves nothing either way.
     */
    public static final String AMBIGUOUS_CAFEBABE = "CAFEBABE_AMBIGUOUS";

    private record Magic(String id, SignatureFamily family, int offset, byte[] bytes) {
    }

    private static final List<Magic> MAGICS = List.of(
            // --- executables -----------------------------------------------------
            magic("PE_EXE", SignatureFamily.EXECUTABLE, 0, 0x4D, 0x5A),                   // "MZ": .exe/.dll/.scr
            magic("ELF", SignatureFamily.EXECUTABLE, 0, 0x7F, 0x45, 0x4C, 0x46),          // Linux binary
            // Shared by Java class files and Mach-O fat binaries, and not
            // separable from the header alone -- see AMBIGUOUS_CAFEBABE.
            magic(AMBIGUOUS_CAFEBABE, SignatureFamily.EXECUTABLE, 0, 0xCA, 0xFE, 0xBA, 0xBE),
            magic("MACH_O_32", SignatureFamily.EXECUTABLE, 0, 0xFE, 0xED, 0xFA, 0xCE),
            magic("MACH_O_64", SignatureFamily.EXECUTABLE, 0, 0xFE, 0xED, 0xFA, 0xCF),
            magic("MACH_O_LE32", SignatureFamily.EXECUTABLE, 0, 0xCE, 0xFA, 0xED, 0xFE),
            magic("MACH_O_LE64", SignatureFamily.EXECUTABLE, 0, 0xCF, 0xFA, 0xED, 0xFE),

            // --- scripts ---------------------------------------------------------
            magic("SHEBANG", SignatureFamily.SCRIPT, 0, 0x23, 0x21),                      // "#!"

            // --- archives (a jar, apk, docx and xlsx are all zip) -----------------
            magic("ZIP", SignatureFamily.ARCHIVE, 0, 0x50, 0x4B, 0x03, 0x04),
            magic("ZIP_EMPTY", SignatureFamily.ARCHIVE, 0, 0x50, 0x4B, 0x05, 0x06),
            magic("RAR", SignatureFamily.ARCHIVE, 0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07),
            magic("SEVEN_ZIP", SignatureFamily.ARCHIVE, 0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C),
            magic("GZIP", SignatureFamily.ARCHIVE, 0, 0x1F, 0x8B),

            // --- documents and images -------------------------------------------
            magic("PDF", SignatureFamily.DOCUMENT, 0, 0x25, 0x50, 0x44, 0x46),            // "%PDF"
            magic("PNG", SignatureFamily.IMAGE, 0, 0x89, 0x50, 0x4E, 0x47),
            magic("JPEG", SignatureFamily.IMAGE, 0, 0xFF, 0xD8, 0xFF),
            magic("GIF", SignatureFamily.IMAGE, 0, 0x47, 0x49, 0x46, 0x38),               // "GIF8"
            magic("BMP", SignatureFamily.IMAGE, 0, 0x42, 0x4D),
            magic("ICO", SignatureFamily.IMAGE, 0, 0x00, 0x00, 0x01, 0x00));

    private static Magic magic(String id, SignatureFamily family, int offset, int... unsigned) {
        byte[] bytes = new byte[unsigned.length];
        for (int i = 0; i < unsigned.length; i++) {
            bytes[i] = (byte) unsigned[i];
        }
        return new Magic(id, family, offset, bytes);
    }

    public Optional<FileSignature> detect(byte[] header) {
        if (header == null || header.length == 0) {
            return Optional.empty();
        }
        for (Magic candidate : MAGICS) {
            if (matches(header, candidate)) {
                return Optional.of(new FileSignature(candidate.id(), candidate.family()));
            }
        }
        return Optional.empty();
    }

    /**
     * Looks for server-side script markers in the header.
     *
     * <p>Used for the polyglot case -- a file that claims to be an image but
     * carries PHP or a script tag in its first bytes is a classic webshell. The
     * caller decides when to apply this, because a genuine {@code .html} upload
     * containing {@code <script>} is not an attack.
     */
    public boolean containsScriptMarkers(byte[] header) {
        if (header == null || header.length == 0) {
            return false;
        }
        String text = new String(header, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return text.contains("<?php")
                || text.contains("<script")
                || text.contains("<%@")       // JSP / ASP directive
                || text.contains("<%=");
    }

    private static boolean matches(byte[] header, Magic candidate) {
        byte[] expected = candidate.bytes();
        int offset = candidate.offset();
        if (header.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (header[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
