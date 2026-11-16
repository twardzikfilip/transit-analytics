package com.transitanalytics.gtfs;

import com.transitanalytics.service.ExternalServiceException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/** Downloads a feed's zip to a temporary file. */
public class GtfsDownloader {

    private final RestClient restClient;
    private final long maxBytes;

    public GtfsDownloader(RestClient restClient, long maxBytes) {
        this.restClient = restClient;
        this.maxBytes = maxBytes;
    }

    /** @return the downloaded file; the caller deletes it */
    public Path download(String url) {
        Path file;
        try {
            file = Files.createTempFile("gtfs-", ".zip");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create a temporary file", ex);
        }
        try {
            restClient.get().uri(URI.create(url)).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw new IOException("HTTP " + response.getStatusCode().value());
                }
                try (InputStream in = response.getBody(); OutputStream out = Files.newOutputStream(file)) {
                    copyLimited(in, out);
                }
                return null;
            });
            return file;
        } catch (RestClientException | IllegalStateException ex) {
            deleteQuietly(file);
            throw new ExternalServiceException(URI.create(url).getHost(), ex);
        }
    }

    private void copyLimited(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Feed is larger than " + maxBytes + " bytes");
            }
            out.write(buffer, 0, n);
        }
    }

    static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover temp file is harmless.
        }
    }
}
