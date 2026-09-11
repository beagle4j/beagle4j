package com.example.bookstore;

import io.github.beagle4j.core.BeagleRuntime;
import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.detect.DetectionReport;
import io.github.beagle4j.core.report.ConsoleReporter;
import io.github.beagle4j.jdbc.BeagleDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the rendered output, and prints it so that a change in the layout is visible
 * in the build log rather than only in someone's terminal.
 */
class ConsoleReportOutputTest {

    private BeagleRuntime beagle;
    private BookstoreService bookstore;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:report-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        try (Connection connection = h2.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE author (id INT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE book (id INT PRIMARY KEY, author_id INT, title VARCHAR(200))");
            for (int a = 1; a <= 12; a++) {
                statement.execute("INSERT INTO author VALUES (" + a + ", 'Author " + a + "')");
                statement.execute("INSERT INTO book VALUES (" + (a * 10) + ", " + a
                        + ", 'Book by author " + a + "')");
            }
        }
        beagle = new BeagleRuntime(BeagleConfig.builder().applicationPackage("com.example").build());
        bookstore = new BookstoreService(BeagleDataSource.wrap(h2));
    }

    @Test
    @DisplayName("renders a finding a developer can act on without opening anything else")
    void rendersAnActionableReport() {
        DetectionReport report =
                beagle.observe("GET /authors", () -> bookstore.listAuthorsWithBooks_nPlusOne());

        // Glyphs and colour pinned explicitly: auto-detection depends on the console the
        // build happens to be attached to, and a test whose expected output changes with
        // the terminal is a test that fails in CI for no reason.
        String output = new ConsoleReporter(88, false, false).render(report);
        System.out.println();
        System.out.println(output);

        assertThat(output)
                .contains("Beagle")
                .contains("GET /authors")
                .contains("N+1 QUERY")
                .contains("critical")
                .contains("confirmed")
                .contains("BookstoreService#loadBooksFor")
                .contains("BookstoreService.java:")
                .contains("x12")
                .contains("12 rows")
                .contains("JOIN FETCH");

        assertThat(output)
                .as("colour must be absent when it was not requested")
                .doesNotContain(String.valueOf((char) 27));

        assertThat(output.lines().map(String::length).max(Integer::compare).orElseThrow())
                .as("nothing should overflow the requested width")
                .isLessThanOrEqualTo(88);
    }

    @Test
    @DisplayName("says so plainly when there is nothing wrong")
    void rendersACleanReport() {
        DetectionReport report =
                beagle.observe("GET /authors", () -> bookstore.listAuthorsWithBooks_singleJoin());

        // Glyphs and colour pinned explicitly: auto-detection depends on the console the
        // build happens to be attached to, and a test whose expected output changes with
        // the terminal is a test that fails in CI for no reason.
        String output = new ConsoleReporter(88, false, false).render(report);
        System.out.println();
        System.out.println(output);

        assertThat(output).contains("No problems found.");
    }

    @Test
    @DisplayName("falls back to ASCII when the output encoding cannot carry box drawing")
    void degradesGracefullyWithoutUnicode() {
        DetectionReport report =
                beagle.observe("GET /authors", () -> bookstore.listAuthorsWithBooks_nPlusOne());

        String unicode = new ConsoleReporter(88, false, true).render(report);
        String ascii = new ConsoleReporter(88, false, false).render(report);

        assertThat(unicode).contains("─").contains("→");
        assertThat(ascii)
                .as("a legacy Windows code page must not produce a wall of question marks")
                .doesNotContain("─")
                .doesNotContain("·")
                .doesNotContain("→")
                .doesNotContain("…");

        // The substitutes have to read correctly on their own: a bare ">" would look
        // like a comparison and a bare "." like the end of a sentence.
        assertThat(ascii).contains("-> 12 rows").contains("... and 10 more");
    }
}
