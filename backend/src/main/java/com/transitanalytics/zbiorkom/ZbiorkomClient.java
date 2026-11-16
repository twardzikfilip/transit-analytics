package com.transitanalytics.zbiorkom;

import com.transitanalytics.service.ExternalServiceException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Thin HTTP client for the zbiorkom.live open API. Further endpoints go here. */
public class ZbiorkomClient {

    private final RestClient restClient;

    public ZbiorkomClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Live vehicle positions in a city, limited to a bounding box.
     *
     * @param bbox "minLon,minLat,maxLon,maxLat"
     */
    public ZbiorkomFeed.Positions positions(String city, String bbox) {
        String[] corners = bbox.split(",");
        if (corners.length != 4) {
            throw new IllegalArgumentException("bbox must be minLon,minLat,maxLon,maxLat but was: " + bbox);
        }
        try {
            // Commas are part of the template, so they are sent literally rather than as %2C.
            return restClient.get()
                    .uri("/{city}/positions/{minLon},{minLat},{maxLon},{maxLat}",
                            city, corners[0].strip(), corners[1].strip(), corners[2].strip(), corners[3].strip())
                    .retrieve()
                    .body(ZbiorkomFeed.Positions.class);
        } catch (RestClientException ex) {
            throw new ExternalServiceException("zbiorkom.live", ex);
        }
    }
}
