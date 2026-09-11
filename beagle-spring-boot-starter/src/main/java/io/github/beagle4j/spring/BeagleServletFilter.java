package io.github.beagle4j.spring;

import io.github.beagle4j.core.BeagleRuntime;
import io.github.beagle4j.core.detect.DetectionReport;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.io.IOException;
import java.util.List;

/**
 * Makes each HTTP request an observed unit of work.
 *
 * <p>The request boundary is the right scope because it is the boundary the N+1 problem
 * is defined against: "this endpoint issues a query per row" only means anything if the
 * queries are grouped by endpoint invocation.
 *
 * <p>The session is closed in a {@code finally}, without exception. On a pooled thread a
 * missed cleanup is worse than a leak: the next request to land on that thread would
 * inherit the previous one's session and report its queries as its own.
 */
public class BeagleServletFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(BeagleServletFilter.class);

    private final BeagleRuntime runtime;
    private final ReportHandler reportHandler;
    private final List<String> excludedPaths;
    private final PathMatcher pathMatcher = new AntPathMatcher();

    public BeagleServletFilter(BeagleRuntime runtime, ReportHandler reportHandler,
                               List<String> excludedPaths) {
        this.runtime = runtime;
        this.reportHandler = reportHandler;
        this.excludedPaths = List.copyOf(excludedPaths);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpRequest) || isExcluded(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        runtime.openSession(label(httpRequest));
        try {
            chain.doFilter(request, response);
        } finally {
            publish();
        }
    }

    /**
     * Reporting is itself wrapped: a diagnostic tool that turns a working response into a
     * 500 because its own analysis threw has done far more damage than the problem it was
     * looking for.
     */
    private void publish() {
        try {
            DetectionReport report = runtime.closeCurrentSession();
            reportHandler.handle(report);
        } catch (RuntimeException e) {
            log.warn("Beagle failed to produce a report for this request", e);
        }
    }

    private boolean isExcluded(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) {
            path = path.substring(context.length());
        }
        for (String pattern : excludedPaths) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private static String label(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
