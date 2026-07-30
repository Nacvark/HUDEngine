package io.github.nacvark.hudengine.core.model;

import java.util.List;

/**
 * Thrown when a configuration cannot produce a correct pack.
 *
 * Carries every problem found rather than just the first, so one restart is enough to see the
 * whole list of what needs fixing.
 */
public final class ConfigurationException extends RuntimeException {

    private final transient List<ModelValidator.Problem> problems;

    public ConfigurationException(List<ModelValidator.Problem> problems) {
        super(summarise(problems));
        this.problems = List.copyOf(problems);
    }

    public List<ModelValidator.Problem> problems() {
        return problems;
    }

    private static String summarise(List<ModelValidator.Problem> problems) {
        long errors = problems.stream()
                .filter(p -> p.severity() == ModelValidator.Severity.ERROR)
                .count();
        return errors == 1
                ? "1 problem in the HUD configuration"
                : errors + " problems in the HUD configuration";
    }
}
