package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Limits on the shape of the request, as opposed to verdicts on its files.
 *
 * <p>Two different things answer "this request is too big", and the screen has
 * to tell them apart: the service counts the files it was handed, while the
 * servlet container refuses a body before any of it reaches the service. This
 * class covers the first, {@link ContainerUploadLimitIntegrationTest} the
 * second.
 */
class UploadRequestShapeIntegrationTest extends IntegrationTestBase {

    private static final byte[] TEXT = "hello, world".getBytes(StandardCharsets.UTF_8);

    /**
     * Tomcat's ceiling on multipart parts, which the container applies before the
     * service sees anything.
     */
    @Value("${server.tomcat.max-part-count}")
    private int maxPartCount;

    private MockMultipartHttpServletRequestBuilder uploadOf(int fileCount) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/files");
        for (int i = 0; i < fileCount; i++) {
            request.file(new MockMultipartFile(
                    "files", "notes-" + i + ".txt", "text/plain", TEXT));
        }
        return request;
    }

    @Test
    @DisplayName("the upload screen can read its limits from the server")
    void limitsArePublished() throws Exception {
        mockMvc.perform(get("/api/v1/files/limits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxFilesPerRequest")
                        .value(storageProperties.getMaxFilesPerRequest()))
                .andExpect(jsonPath("$.maxFileSizeBytes")
                        .value(storageProperties.getMaxFileSize().toBytes()));
    }

    @Test
    @DisplayName("one file over the count is refused for the count, naming the limit")
    void tooManyFilesIsRefusedWithTheLimit() throws Exception {
        int limit = storageProperties.getMaxFilesPerRequest();

        mockMvc.perform(uploadOf(limit + 1))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("TOO_MANY_FILES"))
                .andExpect(jsonPath("$.message").value(
                        "한 번에 최대 %d개까지 업로드할 수 있습니다.".formatted(limit)))
                // A request-level refusal carries no per-file verdicts. The client
                // keys on that absence to decide the failure belongs to the
                // request rather than to any one row.
                .andExpect(jsonPath("$.results").doesNotExist());
    }

    @Test
    @DisplayName("the file limit is still reached at the limit itself")
    void theLimitItselfIsAccepted() throws Exception {
        mockMvc.perform(uploadOf(storageProperties.getMaxFilesPerRequest()))
                .andExpect(status().isOk());
    }

    /**
     * Without this ordering the count check is dead code.
     *
     * <p>The container caps parts before the service counts files, so if that cap
     * were at or below {@code max-files-per-request}, an over-count request would
     * never reach {@code TOO_MANY_FILES}. It would come back as the container's
     * refusal instead -- which cannot say which limit it was, because Spring
     * reports too-many-parts and too-large-a-part as the same exception. The
     * specific message exists only in the window this inequality keeps open.
     */
    @Test
    @DisplayName("the container's part cap leaves room for the file count check")
    void partCapLeavesRoomForTheCountCheck() {
        assertThat(maxPartCount)
                .as("server.tomcat.max-part-count must exceed extguard.storage.max-files-per-request")
                .isGreaterThan(storageProperties.getMaxFilesPerRequest());
    }
}
