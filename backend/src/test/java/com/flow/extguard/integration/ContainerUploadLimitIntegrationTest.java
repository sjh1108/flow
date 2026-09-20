package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.flow.extguard.config.StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * What the servlet container refuses before the application sees it.
 *
 * <p>Needs a real Tomcat: MockMvc does not enforce multipart limits, so every
 * other upload test in this package walks straight past them. That gap is why
 * the mapping below was never measured -- it was reasoned about from the
 * response a user saw.
 *
 * <p><strong>The claim under test:</strong> too many parts and too large a part
 * arrive at the client as the <em>same</em> error. Tomcat catches
 * {@code SizeException} and {@code FileCountLimitExceededException} in one
 * block, and Spring turns both into {@code MaxUploadSizeExceededException}. A
 * message naming only a size would therefore be wrong half the time, and copying
 * it onto every row -- which the screen used to do -- told users that a 68KB
 * file was too large.
 *
 * <p>The container limits are shrunk here so the test can trip them with a few
 * kilobytes instead of 20MB. The message the user gets still states the
 * application's own published limits, because those are the ones they can act
 * on; {@code UploadRequestShapeIntegrationTest} pins the ordering that keeps the
 * two consistent in the real configuration.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.tomcat.max-part-count=3",
                "spring.servlet.multipart.max-file-size=1KB",
        })
@ActiveProfiles("test")
class ContainerUploadLimitIntegrationTest {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private StorageProperties storageProperties;

    private ResponseEntity<String> upload(int fileCount, int bytesEach) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (int i = 0; i < fileCount; i++) {
            String filename = "notes-" + i + ".txt";
            body.add("files", new ByteArrayResource(new byte[bytesEach]) {
                @Override
                public String getFilename() {
                    return filename;
                }
            });
        }

        return RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/v1/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                // Read the error response instead of throwing: its body is the
                // thing under test.
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private void assertRefusedAsRequestLevel(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);

        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"code\":\"FILE_TOO_LARGE\"");
        // Both limits, because the response cannot tell which one was hit.
        assertThat(body)
                .contains("최대 %d개".formatted(storageProperties.getMaxFilesPerRequest()))
                .contains("최대 %dMB".formatted(storageProperties.getMaxFileSize().toMegabytes()));
        // No per-file verdicts: nothing was examined.
        assertThat(body).doesNotContain("\"results\"");
    }

    @Test
    @DisplayName("too many parts is refused as a request-level error, not a per-file one")
    void tooManyPartsIsRefused() {
        assertRefusedAsRequestLevel(upload(4, 8));
    }

    @Test
    @DisplayName("an oversized part is refused the same way, with the same code")
    void oversizedPartIsRefused() {
        assertRefusedAsRequestLevel(upload(1, 2048));
    }

    @Test
    @DisplayName("a request within both container limits reaches the application")
    void aRequestWithinTheLimitsIsJudgedPerFile() {
        ResponseEntity<String> response = upload(2, 8);

        assertThat(response.getStatusCode().value()).isIn(200, 422, 507);
        assertThat(response.getBody()).contains("\"results\"");
    }
}
