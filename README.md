# Beagle4J 🐕

**Finds the database bugs that compile, pass your tests, and fall over in production.**

[![Build](https://github.com/beagle4j/beagle4j/actions/workflows/ci.yml/badge.svg)](https://github.com/beagle4j/beagle4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)

English · [简体中文](README.zh-CN.md)

Beagle watches the JDBC traffic your application actually produces and tells you when it
is doing something that will not survive contact with real data — an N+1 query, the same
row fetched five times in one request, a statement that has quietly become slow.

It works with **Hibernate, JPA, MyBatis, jOOQ, JdbcTemplate or raw JDBC**, because it
watches the layer underneath all of them.

```
── Beagle · GET /authors · 13 queries, 57ms ────────────────────────────────────────────

  N+1 QUERY                                                       critical · confirmed
  13 queries where 2 would do

  parent    BookstoreService#listAuthorsWithBooks  (BookstoreService.java:42)
            select id, name from author order by id   → 12 rows
  repeated  BookstoreService#loadBooksFor  (BookstoreService.java:60)   ×12
            select id, title from book where author_id = ?
            e.g. SELECT id, title FROM book WHERE author_id = 1
            … and 10 more

  why       The preceding query returned 12 rows and this statement then ran 12 times
            — one execution per row. The query count grows with the size of the result
            set, so this gets worse as the table fills up.
  fix       A collection was loaded, then one extra query was issued per element. With
            JPA, fetch the association up front (JOIN FETCH or @EntityGraph). With
            MyBatis or plain JDBC, collect the keys and issue a single batched query
            using IN (...).

── 1 finding, 1 critical, 1 confirmed ──────────────────────────────────────────────────
```

---

## The idea

Ruby has had [Bullet](https://github.com/flyerhzm/bullet) for fifteen years and 7,000
stars. Java has nothing equivalent that is maintained — the closest projects are small
assertion helpers for unit tests, and the best of them stopped being updated in 2024.

Beagle is that tool for the JVM, plus the ones Rails never needed to worry about.

## What it catches

| Problem | Status | What makes it different |
|---|:---:|---|
| **N+1 queries** | ✅ | Correlates repeats against the parent's row count — see below |
| **Repeated identical reads** | ✅ | Captures bind parameters, so it can prove the answer could not have changed |
| **Slow statements** | ✅ | Measured, not guessed |
| Unused eager loading | 🚧 | Fetched a collection nobody read |
| `@Transactional` that silently does nothing | 🚧 | Self-invocation, caught at runtime instead of guessed at by a linter |
| Remote calls inside a transaction | 🚧 | The number one cause of connection-pool exhaustion |
| Unbatched write loops | 🚧 | A different bug from N+1, reported as its own thing |

## Quick start

### Spring Boot

Add the dependency. That is the whole installation.

```xml
<dependency>
  <groupId>io.github.beagle4j</groupId>
  <artifactId>beagle-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Every `DataSource` in the context gets instrumented and every HTTP request becomes an
observed unit of work. One setting is worth adding, because it makes attribution to your
own code much more reliable:

```yaml
beagle:
  application-package: com.acme
```

Findings are written to the log when a request produces any. All twenty settings have IDE
autocompletion; the ones you are most likely to want:

```yaml
beagle:
  enabled: true                 # false disables everything, wrapper included
  n-plus-one-threshold: 3       # repeats before a statement is suspicious
  slow-query-threshold: 200ms   # 0 disables the slow-query rule
  excluded-data-sources: []     # bean names to leave alone
  web:
    excluded-paths: ["/actuator/**", "/static/**"]
```

> **One caveat, stated plainly.** Beagle replaces each `DataSource` bean with a wrapper.
> Code that injects the interface is unaffected — that is almost all code — but anything
> injecting a *concrete* type (`HikariDataSource`, to read pool statistics) will no longer
> match. `unwrap()` still reaches the original, and `beagle.excluded-data-sources` opts a
> named bean out.

### Anything else

`beagle-core` and `beagle-jdbc` have no Spring dependency, so the same engine works from
MyBatis standalone, Quarkus, Micronaut, a test harness, or plain JDBC:

```java
DataSource observed = BeagleDataSource.wrap(myDataSource);

BeagleRuntime beagle = new BeagleRuntime(
        BeagleConfig.builder().applicationPackage("com.acme").build());

DetectionReport report = beagle.observe("GET /authors", () -> service.listAuthors());

System.out.println(new ConsoleReporter().render(report));
```

Outside an open session the instrumentation costs one null check per statement, so
leaving it wired up in an environment where it is switched off is not a problem.

## Why counting queries is not enough

Most tools in this space report "the same statement ran more than N times". That is also
why most of them end up muted: a batch importer legitimately runs one INSERT ten thousand
times, and a tool that calls that a bug trains you to ignore it.

What actually separates an N+1 from a legitimate loop is not the repetition — it is
**where the repetition count came from.** In a real N+1 the number of child queries is
dictated by the size of a result set that was just read: twelve orders produce exactly
twelve follow-up queries.

So Beagle looks backwards from the first repeat, finds the query that preceded the burst,
and checks whether that query's row count explains the number of repeats:

```
  select id, name from author order by id          → 12 rows
  select id, title from book where author_id = ?   × 12 executions
                                                     ↑
                          12 rows in, 12 queries out — the data shaped the traffic
```

When they match, this is not a heuristic. Those are reported as **confirmed**. When no
such parent exists the repetition is still reported, as **likely**, because it is often a
real N+1 whose parent data came from a cache — but you can gate your build on the
confirmed ones alone and never get a flaky failure.

Three further rules keep the noise down:

- **Writes are never called N+1.** A loop of INSERTs is worth batching, but it is a
  different bug with a different fix, and deliberate far more often than not.
- **Identical repeats belong to one detector.** Same statement, same parameters, twelve
  times is wasted work, not an N+1 — it gets reported once, under the right name.
- **The parent search steps over siblings.** When a loop body issues two queries per row,
  the statement before the second one is the first child, not the parent.

## Performance

Stack capture is the expensive part of a tool like this, so Beagle only pays for it where
it can change a conclusion:

1. the first time a statement template is seen — bounded by how many distinct statements
   your application has, so a few dozen, paid once;
2. on repeats, up to 20 samples — repeats are the thing being hunted, so they are worth
   paying for;
3. never after that. A batch job running one statement 100,000 times pays for 20 stack
   walks, not 100,000.

It uses `StackWalker` rather than `new Throwable().getStackTrace()`, which streams frames
lazily instead of materialising an 80-frame Spring stack in full.

> **Benchmarks are not published yet.** A JMH suite is the next thing on the roadmap; until
> the numbers exist, treat the design above as intent rather than as a measured claim.

## Design notes

The reasoning behind the architecture — why dynamic proxies instead of a Java agent, why
a hand-written SQL scanner instead of a regex or a parser, why `ThreadLocal` instead of
`InheritableThreadLocal` — is written up in [`docs/DESIGN-NOTES.md`](docs/DESIGN-NOTES.md).

## Status

**Early. Version 0.1.0, not yet on Maven Central.** The detection engine, the JDBC
instrumentation, the console reporter and the Spring Boot starter all work and are covered
by 31 tests, including end-to-end detection against a real database. The transaction
detectors, a JMH benchmark and an HTML report are next.

Issues and pull requests are welcome, particularly reports of false positives — those are
the bugs that matter most in a tool like this.

## Building

```bash
git clone https://github.com/beagle4j/beagle4j.git
cd beagle4j
mvn test
```

Requires JDK 17 or newer.

## License

[Apache 2.0](LICENSE)
