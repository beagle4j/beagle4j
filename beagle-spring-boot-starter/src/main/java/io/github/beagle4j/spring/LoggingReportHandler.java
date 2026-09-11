package io.github.beagle4j.spring;

import io.github.beagle4j.core.detect.DetectionReport;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.Severity;
import io.github.beagle4j.core.report.ConsoleReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Writes findings to the application log.
 *
 * <p>Logged rather than printed so that the output lands wherever the team already looks,
 * and at a level that matches what was found: a confirmed critical problem deserves
 * {@code WARN}, an informational one does not. Reports with nothing in them are logged at
 * {@code DEBUG}, so that turning the logger up is enough to confirm Beagle is actually
 * watching -- a question every user of a passive tool asks within five minutes.
 */
public class LoggingReportHandler implements ReportHandler {

    private static final Logger log = LoggerFactory.getLogger("io.github.beagle4j.Report");

    private final ConsoleReporter reporter;
    private final Severity minSeverity;
    private final boolean onlyWhenFindings;

    public LoggingReportHandler(BeagleProperties.Report properties) {
        this.reporter = new ConsoleReporter(properties.getWidth(), properties.isColor());
        this.minSeverity = properties.getMinSeverity();
        this.onlyWhenFindings = properties.isOnlyWhenFindings();
    }

    @Override
    public void handle(DetectionReport report) {
        List<Finding> worthReporting = report.atLeast(minSeverity);

        if (worthReporting.isEmpty()) {
            if (!onlyWhenFindings && log.isInfoEnabled()) {
                log.info("\n{}", reporter.render(report));
            } else if (log.isDebugEnabled()) {
                log.debug("{}: {} queries, nothing to report",
                        report.sessionLabel(), report.queryCount());
            }
            return;
        }

        String rendered = reporter.render(report);
        if (containsAtLeast(worthReporting, Severity.WARNING)) {
            log.warn("\n{}", rendered);
        } else {
            log.info("\n{}", rendered);
        }
    }

    private static boolean containsAtLeast(List<Finding> findings, Severity severity) {
        return findings.stream().anyMatch(f -> f.severity().compareTo(severity) >= 0);
    }
}
