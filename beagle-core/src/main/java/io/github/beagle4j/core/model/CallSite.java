package io.github.beagle4j.core.model;

import java.util.Objects;

/**
 * A single frame of user code held responsible for issuing a statement.
 *
 * <p>This is the difference between a report a developer can act on and one they
 * ignore: "you ran 51 queries" is noise, "line 42 of OrderService ran 50 of them" is
 * a bug report.
 */
public record CallSite(String className, String methodName, String fileName, int lineNumber) {

    /** Used when stack capture was disabled or every frame was filtered out. */
    public static final CallSite UNKNOWN = new CallSite("<unknown>", "<unknown>", null, -1);

    public CallSite {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
    }

    /** Stable identity used to group executions. */
    public String id() {
        return className + "#" + methodName + ":" + lineNumber;
    }

    public String simpleClassName() {
        int idx = className.lastIndexOf('.');
        return idx < 0 ? className : className.substring(idx + 1);
    }

    public boolean isKnown() {
        return !UNKNOWN.className().equals(className);
    }

    /** Renders as {@code com.acme.OrderService#loadItems(OrderService.java:42)}. */
    @Override
    public String toString() {
        if (fileName == null || lineNumber < 0) {
            return className + "#" + methodName;
        }
        return className + "#" + methodName + "(" + fileName + ":" + lineNumber + ")";
    }
}
