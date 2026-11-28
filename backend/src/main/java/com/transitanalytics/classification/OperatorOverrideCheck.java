package com.transitanalytics.classification;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when a copy of {@code classification/operators.yml} (an override in {@code ./config}) still lists a
 * company's routes under the old key {@code lines:}: it would be ignored, and the routes would lose their operator
 * without a word.
 */
@Component
public class OperatorOverrideCheck implements SmartInitializingSingleton {

    private final Environment environment;

    public OperatorOverrideCheck(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        check(environment);
    }

    static void check(Environment environment) {
        for (int i = 0; environment.containsProperty("app.operators.companies[" + i + "].code")
                || environment.containsProperty("app.operators.companies[" + i + "].lines[0]"); i++) {
            if (environment.containsProperty("app.operators.companies[" + i + "].lines[0]")) {
                throw new IllegalStateException("classification/operators.yml (an override in ./config) still "
                        + "uses `lines:` for company " + environment.getProperty("app.operators.companies[" + i
                        + "].code") + ": rename it to `routes:`");
            }
        }
    }
}
