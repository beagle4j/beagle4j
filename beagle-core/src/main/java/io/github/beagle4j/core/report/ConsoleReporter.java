package io.github.beagle4j.core.report;

import io.github.beagle4j.core.detect.DetectionReport;
import io.github.beagle4j.core.model.CallSite;
import io.github.beagle4j.core.model.Confidence;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a report for a terminal.
 *
 * <p>Worth more care than it might appear. This output is the entire product for most
 * users: they will read it while annoyed about something else, decide within a few
 * seconds whether it is telling them something true, and act or mute accordingly. So
 * the layout puts the verdict first, the evidence immediately under it, and the fix
 * last -- and every finding states its own confidence, because a developer who is told
 * how sure the tool is will forgive it for being wrong occasionally.
 */
public final class ConsoleReporter {

    private static final int DEFAULT_WIDTH = 88;
    private static final int LABEL_WIDTH = 10;
    private static final int MAX_SAMPLES_SHOWN = 2;

    /** Probe string: if the output encoding can carry these, the nicer glyphs are used. */
    private static final String GLYPHS_UNICODE = "─·→…";

    /**
     * Written as {@code (char) 27} rather than as a literal escape byte or a
     * {@code \\u001B} sequence. A raw control character in a source file breaks diffs and
     * renders as a replacement glyph on GitHub; a unicode escape is processed by the
     * compiler before tokenisation, which makes it a hazard the day someone pastes one
     * into a comment. Building the string is unambiguous and costs nothing at runtime.
     */
    private static final String ESC = String.valueOf((char) 27) + "[";

    private static final String RESET = ESC + "0m";
    private static final String BOLD = ESC + "1m";
    private static final String DIM = ESC + "2m";
    private static final String RED = ESC + "31m";
    private static final String YELLOW = ESC + "33m";
    private static final String BLUE = ESC + "34m";
    private static final String CYAN = ESC + "36m";

    private final int width;
    private final boolean colored;
    private final String line;
    private final String dot;
    private final String arrow;
    private final String ellipsis;

    public ConsoleReporter() {
        this(DEFAULT_WIDTH, colorSupported(), unicodeSupported());
    }

    public ConsoleReporter(int width, boolean colored) {
        this(width, colored, unicodeSupported());
    }

    public ConsoleReporter(int width, boolean colored, boolean unicode) {
        this.width = Math.max(60, width);
        this.colored = colored;
        // The ASCII substitutes are not all single characters -- a lone ">" standing in
        // for an arrow reads as a comparison operator, and a lone "." for an ellipsis
        // reads as a full stop. The rule glyph is the only one that must stay one
        // character wide, because it is what the horizontal rules are built from.
        this.line = unicode ? "─" : "-";
        this.dot = unicode ? "·" : "|";
        this.arrow = unicode ? "→" : "->";
        this.ellipsis = unicode ? "…" : "...";
    }

    /**
     * Whether the stream this output is heading for can actually represent box-drawing
     * characters.
     *
     * <p>It frequently cannot. A Windows console still defaults to a legacy code page --
     * 936 on a Chinese-locale machine, 437 or 1252 elsewhere -- and none of them contain
     * {@code U+2500}, so a report that assumes Unicode greets half its users with a wall
     * of replacement characters on first run. That is a bad first impression earned for
     * the sake of a slightly nicer horizontal line, so the glyphs are asked for rather
     * than assumed, and the ASCII fallback is used whenever the answer is no.
     */
    private static boolean unicodeSupported() {
        try {
            return outputCharset().newEncoder().canEncode(GLYPHS_UNICODE);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static java.nio.charset.Charset outputCharset() {
        java.io.Console console = System.console();
        if (console != null) {
            return console.charset();
        }
        // Redirected output: `stdout.encoding` is what the JVM will actually encode with,
        // and `native.encoding` is the platform default behind it.
        for (String property : new String[] {"stdout.encoding", "native.encoding"}) {
            String name = System.getProperty(property);
            if (name != null && !name.isBlank()) {
                try {
                    return java.nio.charset.Charset.forName(name);
                } catch (RuntimeException ignored) {
                    // Fall through to the next candidate.
                }
            }
        }
        return java.nio.charset.Charset.defaultCharset();
    }

    /**
     * Honours the {@code NO_COLOR} convention, and stays plain when there is no console
     * attached -- which is how output ends up redirected into a CI log, where escape
     * codes are unreadable noise.
     */
    private static boolean colorSupported() {
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        return System.console() != null;
    }

    public String render(DetectionReport report) {
        StringBuilder out = new StringBuilder(1024);

        String heading = " Beagle " + dot + " " + report.sessionLabel() + " "
                + dot + " " + plural(report.queryCount(), "query", "queries") + ", "
                + report.durationMillis() + "ms ";
        out.append(colorise(rule(heading), CYAN)).append('\n');

        if (!report.hasFindings()) {
            out.append('\n').append("  ")
                    .append(colorise("No problems found.", DIM))
                    .append('\n').append('\n');
            out.append(colorise(rule(""), CYAN)).append('\n');
            return out.toString();
        }

        for (Finding finding : report.findings()) {
            out.append('\n');
            renderFinding(out, finding);
        }

        if (report.isTruncated()) {
            out.append('\n').append("  ")
                    .append(colorise("Recording limit reached: counts are a lower bound.", DIM))
                    .append('\n');
        }

        out.append('\n').append(colorise(rule(" " + summarise(report) + " "), CYAN)).append('\n');
        return out.toString();
    }

    private void renderFinding(StringBuilder out, Finding finding) {
        String title = finding.type().label().toUpperCase(java.util.Locale.ROOT);
        String verdict = finding.severity().name().toLowerCase(java.util.Locale.ROOT)
                + " " + dot + " " + describe(finding.confidence());

        int pad = Math.max(1, width - 4 - title.length() - verdict.length());
        out.append("  ")
                .append(colorise(BOLD + title, severityColor(finding.severity())))
                .append(" ".repeat(pad))
                .append(colorise(verdict, severityColor(finding.severity())))
                .append('\n');

        out.append("  ").append(headline(finding)).append('\n').append('\n');

        // The evidence for "one statement ran too many times" and for "several different
        // statements ran unprotected" reads nothing alike, and forcing both through one
        // layout produced a report that labelled two distinct writes as "repeated x2".
        switch (finding.type()) {
            case WRITE_OUTSIDE_TRANSACTION -> renderWriteEvidence(out, finding);
            default -> renderRepeatEvidence(out, finding);
        }

        if (finding.detail() != null) {
            out.append('\n');
            field(out, "why", finding.detail());
        }
        field(out, "fix", finding.remediation());
    }

    /** One statement, run more times than it should have been. */
    private void renderRepeatEvidence(StringBuilder out, Finding finding) {
        if (finding.parentCallSite() != null) {
            field(out, "parent", location(finding.parentCallSite()));
            field(out, "", colorise(finding.parentSqlTemplate(), DIM)
                    + colorise("   " + arrow + " " + finding.parentRowCount() + " rows", BLUE));
        }

        String label = finding.occurrences() > 1 ? "repeated" : "query";
        field(out, label, location(finding.callSite())
                + (finding.occurrences() > 1
                ? colorise("   x" + finding.occurrences(), BOLD + severityColor(finding.severity()))
                : ""));
        field(out, "", colorise(finding.sqlTemplate(), DIM));

        List<String> samples = finding.sampleStatements();
        for (int i = 0; i < Math.min(MAX_SAMPLES_SHOWN, samples.size()); i++) {
            field(out, "", colorise("e.g. " + samples.get(i), DIM));
        }
        if (finding.occurrences() > MAX_SAMPLES_SHOWN) {
            field(out, "", colorise(ellipsis + " and "
                    + (finding.occurrences() - MAX_SAMPLES_SHOWN) + " more", DIM));
        }
        renderTiming(out, finding);
    }

    /** Several different statements, none of them protected. */
    private void renderWriteEvidence(StringBuilder out, Finding finding) {
        field(out, "first", location(finding.callSite()));

        List<String> statements = finding.sampleStatements();
        for (int i = 0; i < statements.size(); i++) {
            field(out, i == 0 ? "writes" : "", colorise(statements.get(i), DIM));
        }
        if (finding.occurrences() > statements.size()) {
            field(out, "", colorise(finding.occurrences()
                    + " write executions in total", DIM));
        }
        renderTiming(out, finding);
    }

    private void renderTiming(StringBuilder out, Finding finding) {
        if (finding.totalDurationMillis() > 0) {
            field(out, "", colorise(finding.totalDurationMillis() + "ms total", DIM));
        }
    }

    private String headline(Finding finding) {
        return switch (finding.type()) {
            case N_PLUS_ONE -> (finding.occurrences() + 1) + " queries where 2 would do";
            case REPEATED_IDENTICAL_QUERY -> finding.occurrences()
                    + " executions of a query whose answer cannot have changed";
            case SLOW_QUERY -> "one query took " + finding.totalDurationMillis() + "ms";
            case WRITE_OUTSIDE_TRANSACTION -> finding.occurrences()
                    + " writes that could be left half-applied";
        };
    }

    /** Renders a labelled block, wrapping the body under a hanging indent. */
    private void field(StringBuilder out, String label, String text) {
        String indent = "  " + " ".repeat(LABEL_WIDTH);
        String paddedLabel = "  " + colorise(pad(label), DIM);
        List<String> lines = wrap(text, width - LABEL_WIDTH - 3);
        if (lines.isEmpty()) {
            return;
        }
        out.append(paddedLabel).append(lines.get(0)).append('\n');
        for (int i = 1; i < lines.size(); i++) {
            out.append(indent).append(lines.get(i)).append('\n');
        }
    }

    private static String pad(String label) {
        if (label.length() >= LABEL_WIDTH) {
            return label.substring(0, LABEL_WIDTH);
        }
        return label + " ".repeat(LABEL_WIDTH - label.length());
    }

    private String location(CallSite site) {
        if (!site.isKnown()) {
            return colorise("<call site not captured>", DIM);
        }
        String where = site.simpleClassName() + "#" + site.displayMethodName();
        String at = site.fileName() != null && site.lineNumber() > 0
                ? "  (" + site.fileName() + ":" + site.lineNumber() + ")"
                : "";
        return colorise(where, BOLD) + colorise(at, DIM);
    }

    /**
     * Wraps on whitespace while measuring only printable characters, so that embedded
     * colour codes do not eat into the line budget and leave ragged output.
     */
    private List<String> wrap(String text, int max) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        StringBuilder line = new StringBuilder();
        int printable = 0;
        for (String word : text.split("\\s+")) {
            int wordLength = visibleLength(word);
            if (printable > 0 && printable + 1 + wordLength > max) {
                lines.add(line.toString());
                line.setLength(0);
                printable = 0;
            }
            if (printable > 0) {
                line.append(' ');
                printable++;
            }
            line.append(word);
            printable += wordLength;
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static int visibleLength(String s) {
        int length = 0;
        boolean inEscape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == (char) 27) {
                inEscape = true;
            } else if (inEscape) {
                if (c == 'm') {
                    inEscape = false;
                }
            } else {
                length++;
            }
        }
        return length;
    }

    private String summarise(DetectionReport report) {
        long critical = report.findings().stream()
                .filter(f -> f.severity() == Severity.CRITICAL).count();
        long confirmed = report.findings().stream()
                .filter(f -> f.confidence() == Confidence.HIGH).count();
        int total = report.findings().size();
        return total + (total == 1 ? " finding" : " findings")
                + (critical > 0 ? ", " + critical + " critical" : "")
                + ", " + confirmed + " confirmed";
    }

    private static String plural(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private String rule(String text) {
        int remaining = width - visibleLength(text) - 2;
        if (remaining < 0) {
            return text;
        }
        return line.repeat(2) + text + line.repeat(remaining);
    }

    private static String describe(Confidence confidence) {
        return switch (confidence) {
            case HIGH -> "confirmed";
            case MEDIUM -> "likely";
            case LOW -> "possible";
        };
    }

    private static String severityColor(Severity severity) {
        return switch (severity) {
            case CRITICAL -> RED;
            case WARNING -> YELLOW;
            case INFO -> BLUE;
        };
    }

    private String colorise(String text, String color) {
        if (!colored || color.isEmpty()) {
            return stripIfPlain(text);
        }
        return color + text + RESET;
    }

    /** Drops inline attribute codes when colour is off, so plain output stays plain. */
    private String stripIfPlain(String text) {
        if (colored) {
            return text;
        }
        return text.replace(BOLD, "").replace(DIM, "").replace(RESET, "")
                .replace(RED, "").replace(YELLOW, "").replace(BLUE, "").replace(CYAN, "");
    }
}
