package com.transitanalytics.classification;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorOverrideCheckTest {

    @Test
    void acceptsRoutesAndRefusesTheOldLinesKey() {
        MockEnvironment current = new MockEnvironment()
                .withProperty("app.operators.companies[0].code", "KPKM")
                .withProperty("app.operators.companies[0].routes[0]", "2");
        assertThatCode(() -> OperatorOverrideCheck.check(current)).doesNotThrowAnyException();

        MockEnvironment old = new MockEnvironment()
                .withProperty("app.operators.companies[0].code", "KPKM")
                .withProperty("app.operators.companies[0].routes[0]", "2")
                .withProperty("app.operators.companies[1].code", "KZK")
                .withProperty("app.operators.companies[1].lines[0]", "1");
        assertThatThrownBy(() -> OperatorOverrideCheck.check(old))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("KZK").hasMessageContaining("`routes:`");
    }
}
