package com.transitanalytics.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteSubtypeTest {

    @Test
    void classifiesSubtypeByRouteName() {
        assertThat(RouteSubtype.classify("1")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify("50")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify("99")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify("100")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("142")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("1000")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("N1")).isEqualTo(RouteSubtype.NIGHT_URBAN);
        assertThat(RouteSubtype.classify("400")).isEqualTo(RouteSubtype.REGIONAL);
        assertThat(RouteSubtype.classify("406")).isEqualTo(RouteSubtype.REGIONAL);
        assertThat(RouteSubtype.classify("499")).isEqualTo(RouteSubtype.REGIONAL);
        assertThat(RouteSubtype.classify("399")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("500")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("700")).as("another operator's regional line").isEqualTo(RouteSubtype.REGIONAL);
        assertThat(RouteSubtype.classify("701")).isEqualTo(RouteSubtype.SUBURBAN);
        assertThat(RouteSubtype.classify("4")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify("Z")).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.classify(null)).isEqualTo(RouteSubtype.URBAN);
        assertThat(RouteSubtype.of("night_urban")).isEqualTo(RouteSubtype.NIGHT_URBAN);
        assertThat(RouteSubtype.SUBURBAN.value()).isEqualTo("suburban");
    }
}
