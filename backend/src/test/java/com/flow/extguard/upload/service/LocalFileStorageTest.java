package com.flow.extguard.upload.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flow.extguard.config.StorageProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path root;

    private LocalFileStorage storage;

    private static final byte[] CONTENT = "hello, world".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.setRoot(root.toString());
        storage = new LocalFileStorage(properties);
    }

    private List<Path> filesOnDisk() throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    /** Fails partway through, the way a full disk or a dropped connection does. */
    private static InputStream failingAfter(int bytes) {
        return new InputStream() {
            private int delivered;

            @Override
            public int read() throws IOException {
                if (delivered++ >= bytes) {
                    throw new IOException("simulated write failure");
                }
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (delivered >= bytes) {
                    throw new IOException("simulated write failure");
                }
                int count = Math.min(len, bytes - delivered);
                for (int i = 0; i < count; i++) {
                    b[off + i] = 'x';
                }
                delivered += count;
                return count;
            }
        };
    }

    @Test
    @DisplayName("stores content under a generated name and computes its digest")
    void storesContent() throws IOException {
        StoredFile stored = storage.store(new ByteArrayInputStream(CONTENT));

        Path onDisk = root.resolve(stored.storedName());
        assertThat(onDisk).exists();
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(CONTENT);
        assertThat(stored.sizeBytes()).isEqualTo(CONTENT.length);
        assertThat(stored.sha256()).hasSize(64);
        assertThat(stored.storedName()).endsWith(".bin").doesNotContain("hello");
    }

    @Test
    @DisplayName("leaves no partial file behind when the write fails")
    void failedWriteLeavesNothingBehind() throws IOException {
        assertThatThrownBy(() -> storage.store(failingAfter(4)))
                .isInstanceOf(IOException.class);

        assertThat(filesOnDisk())
                .as("a failed write must not leave a half file for the disk to carry")
                .isEmpty();
    }

    /**
     * The write succeeded, so the {@code .part} is complete -- and it is exactly
     * then that leaving it behind is worst, because it is indistinguishable from
     * a good file to everything except the sweep 24 hours later. "A failed store
     * leaves nothing behind" has to hold for the move too, not just the write.
     *
     * <p>The stub writes the target before throwing, because that is what the
     * non-atomic fallback can actually do: {@code Files.move} without
     * {@code ATOMIC_MOVE} may copy then delete, and the specification leaves both
     * paths undefined if it throws partway. Throwing before touching the target
     * would leave this case -- an incomplete file under a real {@code .bin} name
     * -- unverified.
     */
    @Test
    @DisplayName("leaves neither .part nor a half-written .bin when the move fails")
    void failedMoveLeavesNothingBehind() throws IOException {
        StorageProperties properties = new StorageProperties();
        properties.setRoot(root.toString());
        LocalFileStorage failingMove = new LocalFileStorage(properties) {
            @Override
            void moveIntoPlace(Path partial, Path target) throws IOException {
                Files.writeString(target, "half-copied target");
                throw new IOException("simulated non-atomic move failure");
            }
        };

        assertThatThrownBy(() -> failingMove.store(new ByteArrayInputStream(CONTENT)))
                .isInstanceOf(IOException.class);

        assertThat(filesOnDisk())
                .as("an incomplete .bin is worse than a .part: nothing can tell it from a good upload")
                .isEmpty();
    }

    @Test
    @DisplayName("no .part file survives a successful store")
    void successfulStoreLeavesNoTempFile() throws IOException {
        storage.store(new ByteArrayInputStream(CONTENT));

        assertThat(filesOnDisk()).hasSize(1);
        assertThat(filesOnDisk().getFirst().getFileName().toString()).endsWith(".bin");
    }

    @Test
    @DisplayName("lists stored names older than the cutoff, and only those")
    void listsOnlyFilesOlderThanTheCutoff() throws IOException {
        StoredFile old = storage.store(new ByteArrayInputStream(CONTENT));
        StoredFile fresh = storage.store(new ByteArrayInputStream(CONTENT));
        age(root.resolve(old.storedName()), Instant.now().minusSeconds(7200));

        List<String> listed = storage.listStoredNamesModifiedBefore(Instant.now().minusSeconds(3600));

        assertThat(listed).containsExactly(old.storedName());
        assertThat(listed).doesNotContain(fresh.storedName());
    }

    @Test
    @DisplayName("deletes abandoned partial writes older than the cutoff")
    void deletesStaleTempFiles() throws IOException {
        Path directory = Files.createDirectories(root.resolve("2026/01/01"));
        Path stale = Files.writeString(directory.resolve("abandoned.bin.part"), "half");
        Path recent = Files.writeString(directory.resolve("in-flight.bin.part"), "half");
        age(stale, Instant.now().minusSeconds(7200));

        int deleted = storage.deleteStaleTempFiles(Instant.now().minusSeconds(3600));

        assertThat(deleted).isEqualTo(1);
        assertThat(stale).doesNotExist();
        assertThat(recent).as("a partial write in progress must survive the sweep").exists();
    }

    @Test
    void reportsUsableSpace() {
        assertThat(storage.usableSpaceBytes()).isPositive();
    }

    /**
     * The listing feeds deletion, so it must not leave the tree it owns. A link
     * planted in the storage root would otherwise hand the sweep a path outside
     * it, and the lexical {@code startsWith(root)} guard compares text and would
     * not notice where the link actually points.
     */
    @Test
    @DisplayName("does not follow a symlink out of the storage root")
    void listingDoesNotFollowSymlinksOutOfTheRoot(@TempDir Path outside) throws IOException {
        Path stranger = Files.write(outside.resolve("elsewhere.bin"), CONTENT);
        age(stranger, Instant.now().minusSeconds(7200));
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support; nothing to prove here
        }

        List<String> listed = storage.listStoredNamesModifiedBefore(Instant.now());

        assertThat(listed).as("a file outside the root is not this sweep's to delete").isEmpty();
        assertThat(stranger).exists();
    }

    // --- delete has to report what happened ---------------------------------
    // The retention job records purged_at from this result. Reporting a delete
    // that did not happen would drop the file out of the quota sum while it still
    // occupies the disk.

    @Test
    @DisplayName("reports success when the file is deleted")
    void deleteReportsSuccess() throws IOException {
        StoredFile stored = storage.store(new ByteArrayInputStream(CONTENT));

        assertThat(storage.delete(stored.storedName())).isTrue();
        assertThat(root.resolve(stored.storedName())).doesNotExist();
    }

    /** Already gone is the outcome the caller wanted, so it counts as success. */
    @Test
    @DisplayName("reports success when the file was already absent")
    void deleteReportsSuccessWhenAlreadyGone() {
        assertThat(storage.delete("2026/01/01/never-existed.bin")).isTrue();
    }

    @Test
    @DisplayName("refuses and reports failure for a name escaping the root")
    void deleteRefusesEscapingName() {
        assertThat(storage.delete("../../etc/passwd")).isFalse();
    }

    private static void age(Path path, Instant to) throws IOException {
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(to));
    }
}
