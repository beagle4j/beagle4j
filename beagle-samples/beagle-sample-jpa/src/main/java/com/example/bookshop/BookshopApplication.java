package com.example.bookshop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class BookshopApplication {

    private static final Logger log = LoggerFactory.getLogger(BookshopApplication.class);

    private static final int AUTHORS = 12;
    private static final int BOOKS_PER_AUTHOR = 3;

    public static void main(String[] args) {
        SpringApplication.run(BookshopApplication.class, args);
    }

    @Bean
    CommandLineRunner seed(AuthorRepository authors) {
        return args -> {
            for (int a = 1; a <= AUTHORS; a++) {
                Author author = new Author("Author " + a);
                for (int b = 1; b <= BOOKS_PER_AUTHOR; b++) {
                    author.addBook(new Book("Book " + a + "-" + b));
                }
                authors.save(author);
            }

            log.info("");
            log.info("  Bookshop is up with {} authors and {} books.",
                    AUTHORS, AUTHORS * BOOKS_PER_AUTHOR);
            log.info("");
            log.info("  Try the slow one first, then the fast one:");
            log.info("");
            log.info("      curl http://localhost:8080/authors/slow");
            log.info("      curl http://localhost:8080/authors/fast");
            log.info("");
            log.info("  Both return identical JSON. Watch this log for the difference.");
            log.info("");
        };
    }
}
