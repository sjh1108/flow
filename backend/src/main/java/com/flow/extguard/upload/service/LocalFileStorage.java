package com.flow.extguard.upload.service;

import com.flow.extguard.config.StorageProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
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
 *
 * <p><strong>What a failed write leaves behind, precisely.</strong> Content goes
 * to a {@code .part} file and is moved into place only once complete. An earlier
 * version wrote straight to the final name, so a write that died partway -- a
 * full disk above all -- left a half file no record pointed at, precisely when
 * the disk could least afford it. The rewrite is a real improvement, but it is
 * worth being exact about how far it reaches, because "a failed write leaves
 * nothing behind" is not true as stated:
 *
 * <ul>
 *   <li><strong>Structural.</strong> A file does not carry a {@code .bin} name
 *       before it is complete. {@link #moveIntoPlace} is the only thing that ever
 *       creates that name, and it runs after the stream is closed.
 *   <li><strong>True where the filesystem supports atomic moves.</strong> A
 *       {@code .bin} is then whole or absent, never partial, because the rename
 *       either happened or did not.
 *   <li><strong>Attempted, not guaranteed.</strong> Both paths are deleted when a
 *       write or a move fails -- but through {@link #deleteQuietly}, which
 *       swallows its own {@code IOException}. A directory that refuses deletes
 *       keeps the leftover. So does a process killed between the write and the
 *       cleanup, which never runs it at all.
 *   <li><strong>Backstop.</strong> Whatever survives is reclaimed by the orphan
 *       sweep once it is past the grace period. That, not the cleanup, is what
 *       makes the leftovers temporary.
 * </ul>
 *
 * <p>The honest summary is that a failed write tries to leave nothing behind and
 * is caught by the sweep when it cannot. Reading it as a guarantee would put
 * weight on the third layer, which is the one that does not hold.
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    static final String SUFFIX = ".bin";
    static final String PART_SUFFIX = ".part";

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

        Path target = directory.resolve(UUID.randomUUID() + SUFFIX);
        Path partial = directory.resolve(target.getFileName() + PART_SUFFIX);
        String relativeName = root.relativize(target).toString().replace('\\', '/');

        // Created with its permissions already restricted. Setting them after the
        // write would leave the file readable under the default umask for as long
        // as the upload takes.
        createRestricted(partial);

        MessageDigest digest = newSha256();
        long written;
        try (DigestInputStream digesting = new DigestInputStream(content, digest);
             OutputStream out = Files.newOutputStream(partial, StandardOpenOption.WRITE)) {
            written = digesting.transferTo(out);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(partial);
            throw e;
        }

        // Only now does the file take a name the rest of the system recognises.
        // Both paths are cleaned on failure, not just the source: a non-atomic
        // move that throws partway leaves the target in an undefined state, so it
        // may exist and be incomplete. An incomplete file under a .bin name is
        // worse than a leftover .part -- nothing can tell it from a good upload,
        // and the orphan sweep will not look at it for another day.
        //
        // Deleting the target is safe because it is a UUID minted for this upload
        // and nothing else can own that path.
        try {
            moveIntoPlace(partial, target);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(partial);
            deleteQuietly(target);
            throw e;
        }
        return new StoredFile(relativeName, written, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Moves the finished file to its final name, atomically where the filesystem
     * supports it.
     *
     * <p>The fallback exists so that a filesystem without {@code ATOMIC_MOVE}
     * degrades instead of failing every upload. It buys availability, not the
     * guarantee: a plain {@code Files.move} may be implemented as copy-then-delete,
     * and the specification leaves the state of both paths <em>undefined</em> if it
     * throws partway, so the target may exist and be incomplete. That is why the
     * caller deletes the target as well as the source -- the atomicity has to be
     * re-established by cleanup, since the move itself no longer provides it.
     *
     * <p>Package-private so a test can make it fail: a move that throws must
     * still leave the directory clean, and there is no portable way to make a
     * real rename fail on demand.
     */
    void moveIntoPlace(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("{} does not support atomic moves; falling back to a plain rename",
                    target.getParent(), e);
            Files.move(partial, target);
        }
    }

    /**
     * Deletes a stored file, reporting whether it is now gone.
     *
     * <p>Returns true when the file was removed <em>or</em> was already absent:
     * both leave the caller with nothing on disk, which is what the caller cares
     * about. Returns false only when the file may still be there, so that a
     * caller recording the deletion does not record one that did not happen.
     */
    @Override
    public boolean delete(String storedName) {
        try {
            Path target = root.resolve(storedName).normalize();
            // Guard against a stored name that somehow escapes the root. Nothing
            // should be able to produce one, which is exactly why it is asserted.
            if (!target.startsWith(root)) {
                log.error("Refusing to delete '{}': resolves outside the storage root", storedName);
                return false;
            }
            Files.deleteIfExists(target);
            return true;
        } catch (IOException e) {
            log.error("Failed to delete '{}'; the file is still on disk", storedName, e);
            return false;
        }
    }

    /**
     * Walks without following symbolic links, deliberately.
     *
     * <p>This walk feeds deletion. A link inside the storage root would otherwise
     * send the sweep outside it, and the {@code startsWith(root)} guard in
     * {@link #delete} is lexical -- it compares path text and would not notice
     * that the resolved target lies elsewhere. Not following links keeps the
     * sweep inside the tree it owns, and sidesteps loops as a side effect.
     *
     * <p>Returns the whole matching set, so the caller holds one name per file
     * past the cutoff. Fine while files are counted in thousands; a store of
     * many small files is what would eventually force a stream here.
     */
    @Override
    public List<String> listStoredNamesModifiedBefore(Instant cutoff) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .filter(path -> modifiedBefore(path, cutoff))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @Override
    public int deleteStaleTempFiles(Instant cutoff) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        List<Path> stale;
        try (Stream<Path> files = Files.walk(root)) {
            stale = files
                    .filter(path -> path.getFileName().toString().endsWith(PART_SUFFIX))
                    .filter(path -> modifiedBefore(path, cutoff))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        int deleted = 0;
        for (Path path : stale) {
            if (deleteQuietly(path)) {
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public long usableSpaceBytes() {
        try {
            // The root may not exist yet on a fresh deployment; the enclosing
            // filesystem is what the number is about either way.
            Path existing = root;
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            return existing == null ? Long.MAX_VALUE : Files.getFileStore(existing).getUsableSpace();
        } catch (IOException e) {
            // Refusing every upload because the free-space probe failed would be
            // worse than the risk it guards against. The quota still applies.
            log.warn("Could not read free space for {}; skipping the disk check", root, e);
            return Long.MAX_VALUE;
        }
    }

    private static boolean modifiedBefore(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            // Deleted underneath us, or unreadable. Either way, leave it alone.
            return false;
        }
    }

    /**
     * Deletes on a best-effort basis, reporting whether the file is now gone.
     *
     * <p>"Quietly" is the point and the limitation. It is called from failure
     * paths that already have an exception to throw, and a cleanup failure must
     * not replace the original cause -- so it logs and returns instead. The
     * callers in {@link #store} discard the result for exactly that reason,
     * which means a refused delete leaves the file there and only the log says
     * so. The orphan sweep is what eventually reclaims it.
     */
    private boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("Failed to delete '{}'; it stays until the orphan sweep reclaims it", path, e);
            return false;
        }
    }

    private void createRestricted(Path file) throws IOException {
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem; fall back without explicit permissions.
            Files.createFile(file);
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

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
