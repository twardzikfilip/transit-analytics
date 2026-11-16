package com.transitanalytics.gtfs;

import com.transitanalytics.service.ExternalServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Downloading a feed: the file is kept on success and removed on any failure, including an oversized feed. */
class GtfsDownloaderTest {

    private static final String URL = "https://feeds.example/gtfs.zip";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private static List<Path> tempFeeds() throws Exception {
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return new ArrayList<>(files.filter(f -> f.getFileName().toString().startsWith("gtfs-")).toList());
        }
    }

    @Test
    void savesTheFeedToATemporaryFile() throws Exception {
        server.expect(requestTo(URL)).andRespond(withSuccess(new byte[]{1, 2, 3}, MediaType.APPLICATION_OCTET_STREAM));

        Path file = new GtfsDownloader(builder.build(), 1024).download(URL);
        try {
            assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void anErrorStatusFailsAndLeavesNoFile() throws Exception {
        List<Path> before = tempFeeds();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> new GtfsDownloader(builder.build(), 1024).download(URL))
                .isInstanceOf(ExternalServiceException.class);
        assertThat(tempFeeds()).isEqualTo(before);
    }

    @Test
    void aFeedLargerThanTheLimitIsAborted() throws Exception {
        List<Path> before = tempFeeds();
        server.expect(requestTo(URL)).andRespond(withSuccess(new byte[2048], MediaType.APPLICATION_OCTET_STREAM));

        assertThatThrownBy(() -> new GtfsDownloader(builder.build(), 1024).download(URL))
                .isInstanceOf(ExternalServiceException.class);
        assertThat(tempFeeds()).isEqualTo(before);
    }
}
