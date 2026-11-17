package com.transitanalytics.dto;

import com.transitanalytics.domain.RouteSubtype;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class RouteDto {

    private RouteDto() {
    }

    public record Request(
            @NotBlank @Size(max = 64) String gtfsId,
            Long agencyId,
            @Size(max = 32) String shortName,
            @Size(max = 255) String longName,
            String description,
            /* GTFS route type code, e.g. 3 = bus. */
            @NotNull Short routeType,
            @Size(max = 512) String url,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String color,
            @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String textColor,
            Integer sortOrder,
            /* Kind of line; when left out, derived from the name (400–499 and 700 regional, other 100 and up suburban, N… night,
               else urban). */
            RouteSubtype subtype) {
    }

    /** @param name what riders see: the short name, or the long name when there is none */
    public record Response(Long id, String gtfsId, Long agencyId, String agencyName, String name, String shortName,
                           String longName, String description, Short routeType, String url, String color,
                           String textColor, Integer sortOrder, RouteSubtype subtype) {
    }

    /**
     * @param q         text in the short or long name
     * @param routeType GTFS route type code
     * @param subtype   kind of line
     */
    public record Filter(String q, Short routeType, RouteSubtype subtype) {
    }
}
