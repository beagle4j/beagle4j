package com.example.bookstore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Stand-in for an application under observation.
 *
 * <p>It lives in {@code com.example} rather than under the library's own package for a
 * reason that is easy to miss: Beagle filters its own frames out of every stack it
 * walks, so a test that issued its queries from inside {@code io.github.beagle4j} would
 * be attributed to nothing at all, and would quietly stop testing attribution. Keeping
 * the fixture in a package a real user might have means the tests exercise the same code
 * path a real user does.
 *
 * <p>Each method is one recognisable shape -- the bug, and the fix -- so the tests read
 * as a description of what the tool does and does not report.
 */
public class BookstoreService {

    private final DataSource dataSource;

    public BookstoreService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * The bug. Walks the authors, then asks for each author's books one author at a
     * time -- the exact shape produced by a lazily-loaded JPA association touched
     * inside a loop.
     */
    public List<Author> listAuthorsWithBooks_nPlusOne() {
        List<Author> authors = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement authorStatement =
                     connection.prepareStatement("SELECT id, name FROM author ORDER BY id");
             ResultSet authorRows = authorStatement.executeQuery()) {

            while (authorRows.next()) {
                int id = authorRows.getInt("id");
                String name = authorRows.getString("name");
                authors.add(new Author(id, name, loadBooksFor(connection, id)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return authors;
    }

    private List<Book> loadBooksFor(Connection connection, int authorId) throws SQLException {
        List<Book> books = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, title FROM book WHERE author_id = ?")) {
            statement.setInt(1, authorId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    books.add(new Book(rows.getInt("id"), rows.getString("title")));
                }
            }
        }
        return books;
    }

    /** The fix. One statement, one round trip, regardless of how many authors there are. */
    public List<Author> listAuthorsWithBooks_singleJoin() {
        List<Author> authors = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT a.id, a.name, b.id AS book_id, b.title "
                             + "FROM author a LEFT JOIN book b ON b.author_id = a.id "
                             + "ORDER BY a.id, b.id");
             ResultSet rows = statement.executeQuery()) {

            Author current = null;
            while (rows.next()) {
                int authorId = rows.getInt("id");
                if (current == null || current.id() != authorId) {
                    current = new Author(authorId, rows.getString("name"), new ArrayList<>());
                    authors.add(current);
                }
                int bookId = rows.getInt("book_id");
                if (!rows.wasNull()) {
                    current.books().add(new Book(bookId, rows.getString("title")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return authors;
    }

    /**
     * Wasted work: the same row fetched repeatedly within one unit of work. The answer
     * cannot have changed between calls, so every execution after the first is pure cost.
     */
    public void lookupSameAuthorRepeatedly(int authorId, int times) {
        try (Connection connection = dataSource.getConnection()) {
            for (int i = 0; i < times; i++) {
                try (PreparedStatement statement =
                             connection.prepareStatement("SELECT name FROM author WHERE id = ?")) {
                    statement.setInt(1, authorId);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            rows.getString("name");
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A deliberate write loop. Worth batching, but it is not an N+1 and a tool that
     * calls it one is a tool that gets muted.
     */
    public void insertAuthorsInLoop(int count) {
        try (Connection connection = dataSource.getConnection()) {
            for (int i = 0; i < count; i++) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO author (id, name) VALUES (?, ?)")) {
                    statement.setInt(1, 1000 + i);
                    statement.setString(2, "Generated Author " + i);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Two different writes that belong together, with autocommit left on. This is what
     * every silent transaction failure looks like from the database's point of view --
     * a {@code @Transactional} method called from inside its own class, the annotation on
     * a private method, a class that was never a Spring bean, or no annotation at all.
     */
    public void addBookAndTouchAuthor_noTransaction(int authorId, int bookId) {
        try (Connection connection = dataSource.getConnection()) {
            insertBook(connection, authorId, bookId);
            markAuthorUpdated(connection, authorId);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The same two writes, actually made atomic. */
    public void addBookAndTouchAuthor_inTransaction(int authorId, int bookId) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try {
                insertBook(connection, authorId, bookId);
                markAuthorUpdated(connection, authorId);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            closeQuietly(connection);
        }
    }

    private void insertBook(Connection connection, int authorId, int bookId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO book (id, author_id, title) VALUES (?, ?, ?)")) {
            statement.setInt(1, bookId);
            statement.setInt(2, authorId);
            statement.setString(3, "Book " + bookId);
            statement.executeUpdate();
        }
    }

    private void markAuthorUpdated(Connection connection, int authorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE author SET name = ? WHERE id = ?")) {
            statement.setString(1, "Author " + authorId + " (updated)");
            statement.setInt(2, authorId);
            statement.executeUpdate();
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Nothing useful to do in a test fixture.
        }
    }

    public record Author(int id, String name, List<Book> books) {
    }

    public record Book(int id, String title) {
    }
}
