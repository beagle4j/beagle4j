package io.github.beagle4j.core.model;

/**
 * How sure Beagle is that a finding is real.
 *
 * <p>Publishing this alongside every finding is a deliberate choice. Detectors that
 * pretend to certainty they do not have teach developers to distrust the whole tool;
 * saying "I am fairly sure, and here is the evidence" keeps the low-confidence
 * findings useful instead of making them poison.
 */
public enum Confidence {

    /**
     * Corroborated by independent evidence. For N+1 this means the number of child
     * executions matched the row count of the preceding parent query -- the signature
     * of iterating a result set.
     */
    HIGH,

    /**
     * The pattern is present but unconfirmed: the statement repeated from one call
     * site often enough to be suspicious, without a parent query to correlate against.
     * Frequently still a real bug, occasionally a legitimate batch loop.
     */
    MEDIUM,

    /** Heuristic only. Reported for completeness, not for gating a build. */
    LOW
}
