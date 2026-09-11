package com.example.bookshop;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Two endpoints returning byte-for-byte identical JSON. One of them is a production
 * incident waiting for the authors table to grow.
 *
 * <p>Neither method mentions SQL, and neither one looks wrong. That is exactly why this
 * class of bug survives code review and reaches production: the cost is invisible at the
 * call site and invisible in testing, because with twelve rows of seed data both
 * endpoints answer instantly.
 */
@RestController
public class AuthorController {

    private final AuthorRepository authors;

    public AuthorController(AuthorRepository authors) {
        this.authors = authors;
    }

    /**
     * The bug. {@code findAll()} loads the authors; {@code getBooks()} is a lazy
     * association, so touching it inside the loop sends Hibernate back to the database
     * once per author.
     */
    @GetMapping("/authors/slow")
    public List<AuthorView> slow() {
        return authors.findAll().stream()
                .map(author -> new AuthorView(
                        author.getName(),
                        author.getBooks().stream().map(Book::getTitle).toList()))
                .toList();
    }

    /** The fix: the same data, fetched up front in a single statement. */
    @GetMapping("/authors/fast")
    public List<AuthorView> fast() {
        return authors.findAllWithBooks().stream()
                .map(author -> new AuthorView(
                        author.getName(),
                        author.getBooks().stream().map(Book::getTitle).toList()))
                .toList();
    }

    public record AuthorView(String name, List<String> books) {
    }
}
