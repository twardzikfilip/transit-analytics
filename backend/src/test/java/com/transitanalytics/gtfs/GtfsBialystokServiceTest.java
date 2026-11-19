package com.transitanalytics.gtfs;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** The Białystok import: download, load, report; one at a time; a broken download is a feed error. */
class GtfsBialystokServiceTest {

    private static final String URL = "https://feeds.example/gtfs.zip";
    private static final String OTHER_URL = "https://feeds.example/other.zip";

    private final GtfsDownloader downloader = mock(GtfsDownloader.class);
    private final GtfsImporter importer = mock(GtfsImporter.class);
    private final org.springframework.context.ApplicationEventPublisher events =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final GtfsBialystokService service = new GtfsBialystokService(downloader, importer,
            new GtfsProperties(Duration.ofSeconds(5), Duration.ofSeconds(30), DataSize.ofMegabytes(50),
                    new GtfsProperties.Feed(URL, null, null),
                    Map.of("other", new GtfsProperties.Feed(OTHER_URL, "other:", "Other line")), "-"),
            ObservationRegistry.NOOP, events);

    private static Path zipWith(String entry) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write("x".getBytes());
        }
        Path file = Files.createTempFile("gtfs-test-", ".zip");
        Files.write(file, bytes.toByteArray());
        return file;
    }

    @Test
    void importsTheDownloadAndDeletesIt() throws Exception {
        Path file = zipWith("stops.txt");
        given(downloader.download(URL)).willReturn(file);
        given(importer.load(any(), anyString())).willReturn(new GtfsImporter.Loaded(Map.of("stop", 2L), Map.of(), List.of("x.txt")));

        GtfsImportResult result = service.importFeed();

        assertThat(result.feed()).isEqualTo("bialystok");
        assertThat(result.source()).isEqualTo(URL);
        assertThat(result.rows()).containsEntry("stop", 2L);
        assertThat(result.ignoredFiles()).containsExactly("x.txt");
        assertThat(file).doesNotExist();
    }

    @Test
    void aDownloadThatIsNotAZipIsAFeedError() throws Exception {
        Path file = Files.createTempFile("gtfs-test-", ".zip");
        Files.writeString(file, "<html>maintenance</html>");
        given(downloader.download(URL)).willReturn(file);

        assertThatThrownBy(service::importFeed).isInstanceOf(GtfsFeedException.class)
                .hasMessageContaining("not a valid zip");
        assertThat(file).doesNotExist();
    }

    @Test
    void onlyOneImportRunsAtATime() throws Exception {
        Path first = zipWith("stops.txt");
        Path second = zipWith("stops.txt");
        // A second import started while the first one is downloading is refused; once done, the next may start.
        given(downloader.download(URL)).willAnswer(invocation -> {
            assertThatThrownBy(service::importFeed).isInstanceOf(GtfsImportInProgressException.class);
            return first;
        }).willReturn(second);
        given(importer.load(any(), anyString())).willReturn(new GtfsImporter.Loaded(Map.of(), Map.of(), List.of()));

        service.importFeed();
        assertThat(service.importFeed().feed()).isEqualTo("bialystok");
    }

    @Test
    void importsAnotherOperatorsFeedWithItsPrefixAndRefreshesThemOnSchedule() throws Exception {
        given(downloader.download(OTHER_URL)).willAnswer(inv -> zipWith("stops.txt"));
        given(importer.load(any(), eq("other:"))).willReturn(new GtfsImporter.Loaded(Map.of(), Map.of(), List.of()));

        GtfsImportResult result = service.importFeed("other");

        assertThat(result.feed()).isEqualTo("other");
        assertThat(result.source()).isEqualTo(OTHER_URL);
        org.mockito.Mockito.verify(importer).load(any(), eq("other:"));
        org.mockito.Mockito.verify(events).publishEvent(
                org.mockito.ArgumentMatchers.<Object>argThat(e -> e instanceof GtfsImported g && g.feed().equals("other")));

        assertThatThrownBy(() -> service.importFeed("nowhere"))
                .isInstanceOf(com.transitanalytics.service.NotFoundException.class);

        // On schedule: every other feed; a failure is only logged.
        given(downloader.download(OTHER_URL)).willThrow(new GtfsFeedException("gone"));
        service.refreshExtra();
        org.mockito.Mockito.verify(downloader, org.mockito.Mockito.times(2)).download(OTHER_URL);
    }
}
