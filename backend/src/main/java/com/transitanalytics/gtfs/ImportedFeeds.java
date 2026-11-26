package com.transitanalytics.gtfs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Each feed's last import ({@code gtfs_feed_import}) and a fingerprint of what it loaded, so that a download that is
 * the same again is not imported.
 */
@Component
public class ImportedFeeds {

    /**
     * A feed's last import.
     *
     * @param fingerprint     see {@link #fingerprint}
     * @param downloadedBytes size of that download
     */
    public record Last(String fingerprint, Instant importedAt, long downloadedBytes) {
    }

    private final JdbcTemplate jdbc;

    public ImportedFeeds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Each feed's last import, by its key. */
    public Map<String, Last> all() {
        return jdbc.query("SELECT feed, fingerprint, imported_at, downloaded_bytes FROM gtfs_feed_import",
                        (rs, i) -> Map.entry(rs.getString(1), new Last(rs.getString(2),
                                rs.getTimestamp(3).toInstant(), rs.getLong(4))))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The feed's last import, if its timetable is still loaded (a feed whose rows are gone is imported again). */
    public Last last(String feed) {
        List<Last> last = jdbc.query("""
                SELECT fingerprint, imported_at, downloaded_bytes FROM gtfs_feed_import i
                WHERE feed = ? AND EXISTS (SELECT 1 FROM trip t WHERE t.feed = i.feed)""",
                (rs, i) -> new Last(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getLong(3)), feed);
        return last.isEmpty() ? null : last.get(0);
    }

    public void remember(String feed, Last last) {
        jdbc.update("""
                INSERT INTO gtfs_feed_import (feed, fingerprint, imported_at, downloaded_bytes) VALUES (?, ?, ?, ?)
                ON CONFLICT (feed) DO UPDATE SET fingerprint = excluded.fingerprint,
                    imported_at = excluded.imported_at, downloaded_bytes = excluded.downloaded_bytes""",
                feed, last.fingerprint(), Timestamp.from(last.importedAt()), last.downloadedBytes());
    }

    /**
     * SHA-256 of the files the import reads (their names and contents) and of the part of the feed it loads. Other
     * files (such as {@code feed_info.txt}, which may change with every build of a feed) and the zip's own dates
     * don't count, so a feed packed again with the same timetable has the same fingerprint.
     */
    public static String fingerprint(ZipFile zip, GtfsProperties.Selection only) throws IOException {
        MessageDigest sha = sha256();
        sha.update(String.valueOf(only).getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[64 * 1024];
        for (GtfsFile file : GtfsFile.values()) {
            ZipEntry entry = zip.getEntry(file.fileName);
            sha.update((file.fileName + (entry == null ? ":none" : ":")).getBytes(StandardCharsets.UTF_8));
            if (entry == null) {
                continue;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                for (int n; (n = in.read(buffer)) > 0; ) {
                    sha.update(buffer, 0, n);
                }
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
