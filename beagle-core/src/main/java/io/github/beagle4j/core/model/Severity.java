package io.github.beagle4j.core.model;

/** How loudly a finding should be reported. */
public enum Severity {

    /** Worth knowing about; will not fail a build. */
    INFO,

    /** Costs real latency today and will get worse as data grows. */
    WARNING,

    /** Scales with the size of the data set; an outage waiting for traffic. */
    CRITICAL
}
