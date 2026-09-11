package io.github.beagle4j.spring;

import io.github.beagle4j.core.detect.DetectionReport;

/**
 * What happens to a report once a unit of work finishes.
 *
 * <p>An extension point rather than a hard-coded log call, because the useful
 * destinations differ: a developer wants the console, a CI job wants a build failure, a
 * team wants the findings on a dashboard. Declaring a bean of this type replaces the
 * default.
 */
@FunctionalInterface
public interface ReportHandler {

    void handle(DetectionReport report);
}
