package com.example.bookstore;

import io.github.beagle4j.core.BeagleRuntime;
import io.github.beagle4j.core.config.BeagleConfig;
import io.github.beagle4j.core.detect.DetectionReport;
import io.github.beagle4j.core.model.Confidence;
import io.github.beagle4j.core.model.Finding;
import io.github.beagle4j.core.model.FindingType;
import io.github.beagle4j.core.model.Severity;
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
 * End-to-end proof against a real database and a real JDBC driver.
 *
 * <p>The unit tests establish that the algorithms are correct in isolation. This one
 * establishes the thing that actually matters: that the instrumentation sees what really
 * happens over JDBC, that row counts and bind parameters survive the trip through the
 * proxies, and that a query is attributed to the line of application code that issued it.
 *
 * <p>Half of these tests assert that nothing is reported. Those are the load-bearing
 * ones -- a detector that finds every N+1 is worthless if it also flags correct code,
 * because it will be switched off before it ever catches anything.
 */
class NPlusOneDetectionTest {

    private static final int AUTHOR_COUNT = 5;
    private static final int BOOKS_PER_AUTHOR = 2;

    private BeagleRuntime beagle;
    private BookstoreService bookstore;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:beagle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        h2.setUser("sa");
        seed(h2);

        DataSource observed = BeagleDataSource.wrap(h2);
        beagle = new BeagleRuntime(BeagleConfig.builder()
                .applicationPackage("com.example")
                .build());
        bookstore = new BookstoreService(observed);
    }

    private void seed(DataSource raw) throws SQLException {
        try (Connection connection = raw.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE author (id INT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE book (id INT PRIMARY KEY, author_id INT, title VARCHAR(200))");
            for (int a = 1; a <= AUTHOR_COUNT; a++) {
                statement.execute("INSERT INTO author VALUES (" + a + ", 'Author " + a + "')");
                for (int b = 1; b <= BOOKS_PER_AUTHOR; b++) {
                    int bookId = a * 10 + b;
                    statement.execute("INSERT INTO book VALUES ("
                            + bookId + ", " + a + ", 'Book " + bookId + "')");
                }
            }
        }
    }

    @Test
    @DisplayName("catches a textbook N+1 and attributes it to the line that caused it")
    void catchesClassicNPlusOne() {
        DetectionReport report =
                beagle.observe("GET /authors", () -> bookstore.listAuthorsWithBooks_nPlusOne());

        assertThat(report.queryCount())
                .as("one query for the authors plus one per author")
                .isEqualTo(1 + AUTHOR_COUNT);

        assertThat(report.ofType(FindingType.N_PLUS_ONE)).hasSize(1);
        Finding finding = report.ofType(FindingType.N_PLUS_ONE).get(0);

        assertThat(finding.occurrences()).isEqualTo(AUTHOR_COUNT);
        assertThat(finding.sqlTemplate()).isEqualTo("select id, title from book where author_id = ?");

        // The correlation is the whole point: the parent returned five rows and five
        // child queries followed, so this is a confirmed N+1 rather than a guess.
        assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(finding.parentRowCount()).isEqualTo(AUTHOR_COUNT);
        assertThat(finding.parentSqlTemplate()).isEqualTo("select id, name from author order by id");

        // Attribution has to survive the whole stack: H2, the proxies, the service.
        assertThat(finding.callSite().className()).isEqualTo(BookstoreService.class.getName());
        assertThat(finding.callSite().methodName()).isEqualTo("loadBooksFor");
        assertThat(finding.callSite().lineNumber()).isPositive();

        // Bind parameters made it through, which is what proves the repeats were
        // different rows rather than the same one fetched over and over.
        assertThat(finding.sampleStatements().get(0)).contains("author_id = 1");
    }

    @Test
    @DisplayName("reports nothing when the same data is fetched with one join")
    void staysSilentOnTheFixedVersion() {
        DetectionReport report =
                beagle.observe("GET /authors", () -> bookstore.listAuthorsWithBooks_singleJoin());

        assertThat(report.queryCount()).isEqualTo(1);
        assertThat(report.findings())
                .as("correct code must produce a clean report")
                .isEmpty();
    }

    @Test
    @DisplayName("catches the same row being fetched repeatedly, without calling it an N+1")
    void catchesRepeatedIdenticalQuery() {
        DetectionReport report =
                beagle.observe("GET /author/1", () -> bookstore.lookupSameAuthorRepeatedly(1, 4));

        assertThat(report.ofType(FindingType.REPEATED_IDENTICAL_QUERY)).hasSize(1);
        Finding finding = report.ofType(FindingType.REPEATED_IDENTICAL_QUERY).get(0);
        assertThat(finding.occurrences()).isEqualTo(4);
        assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);

        // Same statement, same call site, four times -- but identical arguments, so it
        // is wasted work, not an N+1. Reporting it under both names would be noise.
        assertThat(report.ofType(FindingType.N_PLUS_ONE))
                .as("identical repeats belong to exactly one detector")
                .isEmpty();
    }

    @Test
    @DisplayName("does not report a deliberate write loop as an N+1")
    void staysSilentOnBatchInsertLoop() {
        DetectionReport report =
                beagle.observe("POST /import", () -> bookstore.insertAuthorsInLoop(8));

        assertThat(report.queryCount()).isEqualTo(8);
        assertThat(report.ofType(FindingType.N_PLUS_ONE))
                .as("an N+1 is a read problem; a loop of inserts is a different bug")
                .isEmpty();
    }

    @Test
    @DisplayName("only the confirmed findings are strong enough to gate a build")
    void separatesActionableFindingsFromTheRest() {
        DetectionReport report =
                beagle.observe("GET /authors", () -> bookstore.listAuthorsWithBooks_nPlusOne());

        assertThat(report.actionable())
                .isNotEmpty()
                .allSatisfy(f -> {
                    assertThat(f.confidence()).isEqualTo(Confidence.HIGH);
                    assertThat(f.severity()).isNotEqualTo(Severity.INFO);
                });
    }

    @Test
    @DisplayName("records nothing when no session is open")
    void isInertOutsideASession() {
        // Same work, no observation: the instrumentation must not accumulate anything,
        // or a long-running process would leak everything it ever queried.
        bookstore.listAuthorsWithBooks_nPlusOne();

        DetectionReport report =
                beagle.observe("GET /authors", () -> bookstore.listAuthorsWithBooks_singleJoin());
        assertThat(report.queryCount())
                .as("only the work inside the session counts")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the wrapped data source behaves like the one it wraps")
    void remainsTransparentToCallers() throws SQLException {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:transparency-" + UUID.randomUUID());
        DataSource observed = BeagleDataSource.wrap(h2);

        // Pools and ORMs routinely unwrap to reach vendor APIs; refusing would break them.
        assertThat(observed.isWrapperFor(JdbcDataSource.class)).isTrue();
        assertThat(observed.unwrap(JdbcDataSource.class)).isSameAs(h2);
        assertThat(BeagleDataSource.wrap(observed))
                .as("wrapping twice must not stack proxies")
                .isSameAs(observed);

        try (Connection connection = observed.getConnection()) {
            assertThat(connection.isWrapperFor(Connection.class)).isTrue();
            assertThat(connection.getAutoCommit()).isTrue();
        }
    }
}
