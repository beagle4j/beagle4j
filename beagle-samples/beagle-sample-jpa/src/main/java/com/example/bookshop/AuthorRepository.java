package com.example.bookshop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    /**
     * The fix. One statement fetches the authors and their books together, so the query
     * count no longer depends on how many authors there are.
     */
    @Query("SELECT DISTINCT a FROM Author a LEFT JOIN FETCH a.books ORDER BY a.id")
    List<Author> findAllWithBooks();
}
