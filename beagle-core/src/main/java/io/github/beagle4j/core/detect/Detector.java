package io.github.beagle4j.core.detect;

import io.github.beagle4j.core.model.Finding;

import java.util.List;

/**
 * A single rule. Detectors are stateless and are handed a finished
 * {@link DetectionContext}, which keeps them trivially testable and safe to run in
 * any order.
 */
public interface Detector {

    /** Stable identifier, used in configuration to switch an individual rule off. */
    String name();

    List<Finding> detect(DetectionContext context);
}
