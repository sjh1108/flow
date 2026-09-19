package com.flow.extguard.upload.service;

import com.flow.extguard.config.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes accepted uploads to the local filesystem.
 *
 * <p>Two properties matter here:
 *
 * <ul>
 *   <li>The name on disk is a generated UUID. No part of the user's filename is
 *       used to build a path, so path traversal is not merely filtered but
 *       structurally impossible -- there is no user input in the path at all.
 *   <li>Nothing is ever served back out. Handing an uploaded file to a browser is
 *       where the real danger lies, so this class has no read method.
 * </ul>
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    private final Path root;

    public LocalFileStorage(StorageProperties storageProperties) {
        this.root = Path.of(storageProperties.getRoot()).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(InputStream content) throws IOException {
        // Date-based fan-out keeps any single directory small.
        Path directory = root.resolve(LocalDate.now().format(DATE_PATH));
        createDirectories(directory);

        Path target = directory.resolve(UUID.randomUUID() + ".bin");
        String relativeName = root.relativize(target).toString().replace('\\', '/');

        MessageDigest digest = newSha256();
        long written;
        try (DigestInputStream digesting = new DigestInputStream(content, digest);
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            written = digesting.transferTo(out);
        }

        restrictPermissions(target);
        return new StoredFile(relativeName, written, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public void delete(String storedName) {
        try {
            Path target = root.resolve(storedName).normalize();
            // Guard against a stored name that somehow escapes the root. Nothing
            // should be able to produce one, which is exactly why it is asserted.
            if (!target.startsWith(root)) {
                log.error("Refusing to delete '{}': resolves outside the storage root", storedName);
                return;
            }
            Files.deleteIfExists(target);
        } catch (IOException e) {
            // The caller is already handling a failure; an orphaned file is the
            // lesser problem and is recorded for later reconciliation.
            log.error("Failed to delete '{}' while compensating for a failed write", storedName, e);
        }
    }

    private void createDirectories(Path directory) throws IOException {
        if (Files.exists(directory)) {
            return;
        }
        try {
            Files.createDirectories(directory,
                    PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem; fall back without explicit permissions.
            Files.createDirectories(directory);
        }
    }

    private void restrictPermissions(Path target) {
        try {
            Files.setPosixFilePermissions(target, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Could not set POSIX permissions on {}", target, e);
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
