package com.transitanalytics.zbiorkom;

import com.transitanalytics.service.ExternalServiceException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Thin HTTP client for the zbiorkom.live open API. Further endpoints go here. */
public class ZbiorkomClient {

    private final RestClient restClient;
    private final JsonMapper jsonMapper;

    public ZbiorkomClient(RestClient restClient, JsonMapper jsonMapper) {
        this.restClient = restClient;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Live vehicle positions in a city, limited to a bounding box.
     *
     * @param bbox "minLon,minLat,maxLon,maxLat"
     */
    public ZbiorkomFeed.Positions positions(String city, String bbox) {
        return parsePositions(positionsRaw(city, bbox));
    }

    /** The same response as {@link #positions}, as received (for archiving). */
    public byte[] positionsRaw(String city, String bbox) {
        String[] corners = bbox.split(",");
        if (corners.length != 4) {
            throw new IllegalArgumentException("bbox must be minLon,minLat,maxLon,maxLat but was: " + bbox);
        }
        try {
            // Commas are part of the template, so they are sent literally rather than as %2C.
            byte[] body = restClient.get()
                    .uri("/{city}/positions/{minLon},{minLat},{maxLon},{maxLat}",
                            city, corners[0].strip(), corners[1].strip(), corners[2].strip(), corners[3].strip())
                    .retrieve()
                    .body(byte[].class);
            return body == null ? new byte[0] : body;
        } catch (RestClientException ex) {
            throw new ExternalServiceException("zbiorkom.live", ex);
        }
    }

    /** Parses a positions response, e.g. one read back from the archive or a saved file. */
    public ZbiorkomFeed.Positions parsePositions(byte[] json) {
        if (json.length == 0) {
            return new ZbiorkomFeed.Positions(java.util.List.of());
        }
        try {
            return jsonMapper.readValue(json, ZbiorkomFeed.Positions.class);
        } catch (JacksonException ex) {
            throw new ExternalServiceException("zbiorkom.live", ex);
        }
    }
}
