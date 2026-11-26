package com.transitanalytics.gtfs;

import com.transitanalytics.DbIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Each feed's last import: kept, replaced, and forgotten while the feed has no trips loaded. */
@DbIntegrationTest
@Transactional
class ImportedFeedsIntegrationTest {

    private static final String FEED = "imported-feeds-test";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ImportedFeeds imported;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM gtfs_feed_import WHERE feed = ?", FEED);
        jdbc.update("DELETE FROM trip WHERE feed = ?", FEED);
        jdbc.update("DELETE FROM route WHERE feed = ?", FEED);
    }

    @Test
    void remembersTheLastImportWhileTheFeedIsLoaded() {
        Instant first = Instant.parse("2026-11-25T03:50:00Z");
        Instant second = Instant.parse("2026-11-26T03:50:00Z");
        imported.remember(FEED, new ImportedFeeds.Last("a", first, 10));
        imported.remember(FEED, new ImportedFeeds.Last("b", second, 20));

        assertThat(imported.all()).containsEntry(FEED, new ImportedFeeds.Last("b", second, 20));
        assertThat(imported.last(FEED)).as("no trips loaded: import again").isNull();

        long route = jdbc.queryForObject("INSERT INTO route (feed, gtfs_id, route_type) VALUES (?, 'R', 3) "
                + "RETURNING id", Long.class, FEED);
        jdbc.update("INSERT INTO trip (feed, gtfs_id, route_id, service_id) VALUES (?, 'T', ?, 'S')", FEED, route);

        assertThat(imported.last(FEED)).isEqualTo(new ImportedFeeds.Last("b", second, 20));
    }
}
