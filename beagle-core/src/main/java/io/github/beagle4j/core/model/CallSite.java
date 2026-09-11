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

    /**
     * The method name as a developer would write it.
     *
     * <p>Work done inside a lambda runs in a synthetic method the compiler names
     * {@code lambda$slow$0}, and that is what appears on the stack. It is accurate and it
     * is unhelpful: a report pointing at {@code AuthorController#lambda$slow$0} makes a
     * reader stop and work out what they are looking at, when the answer is simply
     * "inside {@code slow()}" -- which the line number already pins down exactly.
     *
     * <p>The raw name stays available from {@link #methodName()}; only the rendering
     * changes.
     */
    public String displayMethodName() {
        if (!methodName.startsWith("lambda$")) {
            return methodName;
        }
        int start = methodName.indexOf('$') + 1;
        int end = methodName.lastIndexOf('$');
        if (end <= start) {
            return methodName;
        }
        String enclosing = methodName.substring(start, end);
        // javac names a lambda nested inside another lambda `lambda$null$1`, which would
        // render as "null". Better to show the synthetic name than a misleading one.
        if (enclosing.isEmpty() || enclosing.equals("null") || enclosing.contains("$")) {
            return methodName;
        }
        return enclosing;
    }

    public boolean isKnown() {
        return !UNKNOWN.className().equals(className);
    }

    /** Renders as {@code com.acme.OrderService#loadItems(OrderService.java:42)}. */
    @Override
    public String toString() {
        String method = displayMethodName();
        if (fileName == null || lineNumber < 0) {
            return className + "#" + method;
        }
        return className + "#" + method + "(" + fileName + ":" + lineNumber + ")";
    }
}
