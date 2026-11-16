package com.transitanalytics.gtfs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GtfsCsvTest {

    private static String transcode(String csv, List<String> columns) throws IOException {
        StringBuilder out = new StringBuilder();
        GtfsCsv.transcode(new StringReader(csv), columns, out);
        return out.toString();
    }

    @Test
    void reordersColumnsDropsExtrasAndWritesMissingOnesAsNull() throws IOException {
        String csv = "stop_name,stop_id,extra\nCentrum,1,x\n";

        assertThat(transcode(csv, List.of("stop_id", "stop_name", "stop_code"))).isEqualTo("1,Centrum,\n");
    }

    @Test
    void emptyValuesBecomeNullAndQuotedCommasSurvive() throws IOException {
        String csv = "stop_id,stop_name,stop_desc\n7,\"Lipowa, Rynek\",\n";

        assertThat(transcode(csv, List.of("stop_id", "stop_name", "stop_desc")))
                .isEqualTo("7,\"Lipowa, Rynek\",\n");
    }

    @Test
    void ignoresByteOrderMarkWhitespaceAndCrlf() throws IOException {
        String csv = "﻿trip_id , stop_sequence\r\n  A1 ,0\r\n\r\nA1,1\r\n";

        assertThat(transcode(csv, List.of("trip_id", "stop_sequence"))).isEqualTo("A1,0\nA1,1\n");
    }

    @Test
    void handlesHeaderOnlyFiles() throws IOException {
        assertThat(transcode("service_id,date,exception_type\n", List.of("service_id"))).isEmpty();
    }
}
