package com.transitanalytics.service;

import com.transitanalytics.domain.Route;
import com.transitanalytics.domain.RouteSubtype;
import com.transitanalytics.dto.RouteDto;
import com.transitanalytics.repository.AgencyRepository;
import com.transitanalytics.repository.RouteRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** Routes: a subtype from the line name unless one is given, and references to agencies that must exist. */
class RouteServiceTest {

    private final RouteRepository routes = mock(RouteRepository.class);
    private final AgencyRepository agencies = mock(AgencyRepository.class);
    private final RouteService service = new RouteService(routes, agencies);

    private static RouteDto.Request request(String shortName, String longName, Long agencyId, RouteSubtype subtype) {
        return new RouteDto.Request("G-" + longName, agencyId, shortName, longName, null, (short) 3, null, "#1E88E5",
                null, null, subtype);
    }

    @Test
    void theSubtypeFollowsTheLineNameUnlessGiven() {
        given(routes.save(any(Route.class))).willAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.create(request(null, "105", null, null)).subtype()).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(service.create(request("N2", "Night 2", null, null)).subtype())
                .as("the short name is what riders see").isEqualTo(RouteSubtype.NIGHT_URBAN);
        assertThat(service.create(request(null, "22", null, null)).subtype()).isEqualTo(RouteSubtype.URBAN);
        assertThat(service.create(request(null, "22", null, RouteSubtype.REGIONAL)).subtype())
                .isEqualTo(RouteSubtype.REGIONAL);
    }

    @Test
    void theResponseShowsTheDisplayNameAndColours() {
        given(routes.save(any(Route.class))).willAnswer(invocation -> invocation.getArgument(0));

        RouteDto.Response r = service.create(request(null, "103", null, null));

        assertThat(r.name()).isEqualTo("103");
        assertThat(r.gtfsId()).isEqualTo("G-103");
        assertThat(r.color()).isEqualTo("#1E88E5");
        assertThat(r.agencyId()).isNull();
    }

    @Test
    void anUnknownAgencyIsNotFound() {
        given(agencies.findById(4L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(null, "103", 4L, null)))
                .isInstanceOf(NotFoundException.class).hasMessage("Agency with id 4 not found");
    }
}
