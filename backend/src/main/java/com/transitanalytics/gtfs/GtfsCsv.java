package com.transitanalytics.gtfs;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Rewrites a GTFS CSV file into the column order of a staging table, in the CSV form PostgreSQL COPY reads. */
final class GtfsCsv {

    /** Empty values are written unquoted, which COPY reads as NULL. */
    private static final CSVFormat COPY_FORMAT = CSVFormat.DEFAULT.builder()
            .setNullString("")
            .setRecordSeparator('\n')
            .get();

    private static final CSVFormat GTFS_FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .get();

    private GtfsCsv() {
    }

    /**
     * Reads GTFS rows from {@code in} and writes the given {@code columns} to {@code out}; columns missing from the
     * file are written as NULL and extra columns are dropped. A UTF-8 byte order mark before the header is ignored.
     *
     * @return number of rows written
     */
    static long transcode(Reader in, List<String> columns, Appendable out) throws IOException {
        try (CSVParser parser = GTFS_FORMAT.parse(new BomSkippingReader(in))) {
            Map<String, Integer> index = new HashMap<>();
            parser.getHeaderMap().forEach((name, i) -> index.put(name.strip().replace("﻿", ""), i));
            int[] positions = columns.stream().mapToInt(c -> index.getOrDefault(c, -1)).toArray();

            CSVPrinter printer = new CSVPrinter(out, COPY_FORMAT);
            Object[] values = new Object[positions.length];
            long rows = 0;
            for (CSVRecord record : parser) {
                for (int i = 0; i < positions.length; i++) {
                    int p = positions[i];
                    String value = p >= 0 && p < record.size() ? record.get(p) : null;
                    values[i] = value == null || value.isEmpty() ? null : value;
                }
                printer.printRecord(values);
                rows++;
            }
            printer.flush();
            return rows;
        }
    }

    /** Drops a leading U+FEFF so the first header name parses cleanly. */
    private static final class BomSkippingReader extends java.io.FilterReader {
        private boolean start = true;

        BomSkippingReader(Reader in) {
            super(in);
        }

        @Override
        public int read(char[] buf, int off, int len) throws IOException {
            int n = super.read(buf, off, len);
            if (start && n > 0) {
                start = false;
                if (buf[off] == '﻿') {
                    System.arraycopy(buf, off + 1, buf, off, n - 1);
                    return n == 1 ? read(buf, off, len) : n - 1;
                }
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            int c = super.read();
            if (start) {
                start = false;
                if (c == '﻿') {
                    return super.read();
                }
            }
            return c;
        }
    }
}
