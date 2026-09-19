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

    private static void age(Path path, Instant to) throws IOException {
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(to));
    }
}
