package io.github.beagle4j.core.model;

/**
 * The catalogue of problems Beagle knows how to recognise.
 *
 * <p>Each constant carries the human-facing label and the default remediation hint,
 * so that a report never says only "something is wrong" -- it always says what to do
 * next. A finding a developer cannot act on is a finding they will mute.
 */
public enum FindingType {

    N_PLUS_ONE(
            "N+1 query",
            "A collection was loaded, then one extra query was issued per element. "
                    + "With JPA, fetch the association up front (JOIN FETCH or @EntityGraph). "
                    + "With MyBatis or plain JDBC, collect the keys and issue a single "
                    + "batched query using IN (...)."),

    REPEATED_IDENTICAL_QUERY(
            "Repeated identical query",
            "The exact same statement with the exact same parameters ran more than once "
                    + "in a single unit of work. The result cannot have changed, so this is "
                    + "wasted work. Hoist the call out of the loop, or cache the value for "
                    + "the duration of the request."),

    SLOW_QUERY(
            "Slow query",
            "This statement exceeded the configured latency budget. Check the execution "
                    + "plan; most cases are a missing index or an unbounded scan.");

    private final String label;
    private final String remediation;

    FindingType(String label, String remediation) {
        this.label = label;
        this.remediation = remediation;
    }

    public String label() {
        return label;
    }

    public String remediation() {
        return remediation;
    }
}
