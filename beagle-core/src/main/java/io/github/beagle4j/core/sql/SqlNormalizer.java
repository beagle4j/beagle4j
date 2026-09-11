package io.github.beagle4j.core.sql;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Reduces a statement to a template, so that executions that differ only in their
 * parameters are recognised as the same statement.
 *
 * <p>This is a character scanner rather than a regular expression, because SQL string
 * literals can contain anything -- including comment markers, semicolons and escaped
 * quotes. A regex that strips {@code '...'} will happily mangle
 * {@code where note = 'it''s fine -- really'}, and a normaliser that mangles input
 * silently produces wrong groupings, which produces wrong findings.
 *
 * <p>It is deliberately not a SQL parser. Parsing every dialect correctly is a project
 * in itself, and everything downstream only needs stable grouping, not a syntax tree.
 *
 * <h2>What it does</h2>
 * <ul>
 *   <li>string literals, numbers, and hex/binary literals become {@code ?}</li>
 *   <li>comments are removed</li>
 *   <li>runs of whitespace collapse to a single space</li>
 *   <li>{@code IN (?, ?, ?)} collapses to {@code IN (?)}, so that a MyBatis
 *       {@code foreach} over 3 ids and one over 300 share a template</li>
 *   <li>multi-row {@code VALUES (?),(?),(?)} collapses the same way</li>
 *   <li>the result is lower-cased, so hand-written SQL that varies only in keyword
 *       case still groups together</li>
 * </ul>
 */
public final class SqlNormalizer {

    private static final Pattern IN_LIST =
            Pattern.compile("\\bin\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)");

    private static final Pattern VALUES_LIST =
            Pattern.compile("\\bvalues\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)"
                    + "(?:\\s*,\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\))+");

    /** Statements longer than this are truncated; nothing useful is lost for grouping. */
    private static final int MAX_TEMPLATE_LENGTH = 4_000;

    /**
     * Bounded so that an application generating unique SQL forever (string-concatenated
     * literals, say) cannot turn the cache into a memory leak. Once full we simply stop
     * adding: normalisation still works, it just stops being memoised.
     */
    private static final int MAX_CACHE_ENTRIES = 2_048;

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String normalize(String sql) {
        if (sql == null || sql.isEmpty()) {
            return "";
        }
        String cached = cache.get(sql);
        if (cached != null) {
            return cached;
        }
        String template = computeTemplate(sql);
        if (cache.size() < MAX_CACHE_ENTRIES) {
            cache.putIfAbsent(sql, template);
        }
        return template;
    }

    int cacheSize() {
        return cache.size();
    }

    private static String computeTemplate(String sql) {
        String stripped = stripLiteralsAndComments(sql);
        String collapsed = IN_LIST.matcher(stripped).replaceAll("in (?)");
        collapsed = VALUES_LIST.matcher(collapsed).replaceAll("values (?)");
        if (collapsed.length() > MAX_TEMPLATE_LENGTH) {
            collapsed = collapsed.substring(0, MAX_TEMPLATE_LENGTH) + " ...";
        }
        return collapsed;
    }

    /**
     * Single pass over the statement. The scanner is a small state machine; the states
     * are implicit in the branches below, which keeps it fast enough to sit on the hot
     * path of every query.
     */
    private static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        boolean lastWasSpace = false;

        while (i < n) {
            char c = sql.charAt(i);

            // -- line comment, runs to end of line
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }

            // /* block comment */ -- note these do not nest in standard SQL
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(i + 2, n);
                continue;
            }

            // 'string literal', with '' as the escape for a single quote
            if (c == '\'') {
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\\' && i + 1 < n) {
                        i += 2;              // MySQL-style backslash escape
                        continue;
                    }
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;          // doubled quote: still inside the literal
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append('?');
                lastWasSpace = false;
                continue;
            }

            // "quoted identifier" and `backtick identifier` are names, not values:
            // they are kept, but lower-cased along with everything else.
            if (c == '"' || c == '`') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    out.append(Character.toLowerCase(d));
                    i++;
                    if (d == quote) {
                        break;
                    }
                }
                lastWasSpace = false;
                continue;
            }

            // 0x1F / X'1F' style literals
            if (c == '0' && i + 1 < n && (sql.charAt(i + 1) == 'x' || sql.charAt(i + 1) == 'X')
                    && !isIdentifierPart(previousSignificant(out))) {
                i += 2;
                while (i < n && isHexDigit(sql.charAt(i))) {
                    i++;
                }
                out.append('?');
                lastWasSpace = false;
                continue;
            }

            // A numeric literal -- but only when it does not continue an identifier,
            // so that `column1` and `t2.id` survive intact.
            if (isDigit(c) && !isIdentifierPart(previousSignificant(out))) {
                while (i < n && (isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                // exponent form: 1e-9
                if (i < n && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
                    int save = i;
                    i++;
                    if (i < n && (sql.charAt(i) == '+' || sql.charAt(i) == '-')) {
                        i++;
                    }
                    if (i < n && isDigit(sql.charAt(i))) {
                        while (i < n && isDigit(sql.charAt(i))) {
                            i++;
                        }
                    } else {
                        i = save;
                    }
                }
                out.append('?');
                lastWasSpace = false;
                continue;
            }

            if (Character.isWhitespace(c)) {
                if (!lastWasSpace && out.length() > 0) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                i++;
                continue;
            }

            out.append(Character.toLowerCase(c));
            lastWasSpace = false;
            i++;
        }

        // trailing separator noise adds nothing to the grouping key
        int end = out.length();
        while (end > 0 && (out.charAt(end - 1) == ' ' || out.charAt(end - 1) == ';')) {
            end--;
        }
        out.setLength(end);
        return out.toString();
    }

    /** Last character actually emitted, or 0 if none. Drives the identifier-vs-literal decision. */
    private static char previousSignificant(StringBuilder out) {
        return out.length() == 0 ? 0 : out.charAt(out.length() - 1);
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '$';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
