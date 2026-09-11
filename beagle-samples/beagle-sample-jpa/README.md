# Sample: Spring Boot + JPA

A running application with a real Hibernate lazy-loading N+1 in it, and the fixed version
of the same endpoint next to it.

## Run it

```bash
mvn -pl beagle-samples/beagle-sample-jpa -am spring-boot:run
```

Then, in another terminal:

```bash
curl http://localhost:8080/authors/slow
curl http://localhost:8080/authors/fast
```

Both return byte-for-byte identical JSON. Watch the application log.

## What you should see

`/authors/slow` produces this — copied from an actual run, not written by hand:

```
── Beagle · GET /authors/slow · 13 queries, 119ms ──────────────────────────────────────

  N+1 QUERY                                                       critical · confirmed
  13 queries where 2 would do

  parent    AuthorController#slow (AuthorController.java:33)
            select a1_0.id,a1_0.name from author a1_0 → 12 rows
  repeated  AuthorController#slow (AuthorController.java:36) x12
            select b1_0.author_id,b1_0.id,b1_0.title from book b1_0 where
            b1_0.author_id=?
            e.g. select b1_0.author_id,b1_0.id,b1_0.title from book b1_0 where
            b1_0.author_id=1
            … and 10 more

  why       The preceding query returned 12 rows and this statement then ran 12 times
            -- one execution per row. The query count grows with the size of the result
            set, so this gets worse as the table fills up.
  fix       A collection was loaded, then one extra query was issued per element. With
            JPA, fetch the association up front (JOIN FETCH or @EntityGraph). With
            MyBatis or plain JDBC, collect the keys and issue a single batched query
            using IN (...).

── 1 finding, 1 critical, 1 confirmed ──────────────────────────────────────────────────
```

Line 33 is `authors.findAll()`. Line 36 is `author.getBooks()`. Both are exactly right,
and neither of them looks like SQL.

`/authors/fast` produces nothing at all, because there is nothing to say.

## The point

Look at [`AuthorController`](src/main/java/com/example/bookshop/AuthorController.java).
Neither method mentions SQL. Neither looks wrong. `getBooks()` is an ordinary getter, and
the `LAZY` fetch type on the association is the JPA default and the right choice.

Nothing in this code is a mistake in the sense a reviewer would catch. The cost is
invisible at the call site, and invisible in testing — with twelve authors both endpoints
answer instantly. It only becomes an incident when the table has fifty thousand rows in
it, by which time the commit that caused it is a year old.

That is the gap this tool exists to close: making the cost visible at the moment the code
is written, in the log of the developer who wrote it.
