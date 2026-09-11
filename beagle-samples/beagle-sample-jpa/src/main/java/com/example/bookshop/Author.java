package com.example.bookshop;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;

import java.util.ArrayList;
import java.util.List;

/**
 * Note the {@code LAZY} fetch type, which is the JPA default and is almost always the
 * right choice. Nothing about this entity is wrong.
 *
 * <p>That is the point of the demonstration: the N+1 in this sample is not caused by a
 * mistake in the mapping. It is caused by touching {@code getBooks()} inside a loop, three
 * files away, where nobody is thinking about SQL.
 */
@Entity
public class Author {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @OneToMany(mappedBy = "author", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private List<Book> books = new ArrayList<>();

    protected Author() {
        // for JPA
    }

    public Author(String name) {
        this.name = name;
    }

    public void addBook(Book book) {
        books.add(book);
        book.setAuthor(this);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<Book> getBooks() {
        return books;
    }
}
