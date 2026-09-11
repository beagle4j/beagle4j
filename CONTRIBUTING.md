# Contributing

## The most useful thing you can send

A **false positive**. There is [an issue template for it](.github/ISSUE_TEMPLATE/false-positive.yml).

This is not politeness. A tool like this is only worth having if people believe it, and a
single wrong finding costs more than ten correct ones — it teaches developers to skim past
the whole report, and a report nobody reads catches nothing. Every false positive is
treated as a real bug, and several of the rules that exist today are there because an
obvious implementation would have produced one.

## Building

```bash
mvn test
```

JDK 17 or newer. Nothing else to install — the tests run against H2 in memory.

## Layout

| Module | What it is |
|---|---|
| `beagle-core` | The detection engine. Depends on the JDK and SLF4J, nothing else. |
| `beagle-jdbc` | JDBC instrumentation via dynamic proxies. |
| `beagle-spring-boot-starter` | Auto-configuration. The only module that knows Spring exists. |
| `beagle-benchmark` | JMH benchmarks. Not published. |
| `beagle-samples` | Runnable demonstrations. Not published. |

**`beagle-core` and `beagle-jdbc` must never gain a Spring dependency.** They are what
makes the tool usable from MyBatis standalone, Quarkus, Micronaut or plain JDBC, and that
boundary is worth defending in review.

## Adding a detector

1. Implement `Detector`. They are stateless and receive a finished `DetectionContext`.
2. Register it in `BeagleRuntime.defaultDetectors()`.
3. Add the type to `FindingType` with a remediation string. The exhaustive `switch`
   statements will fail to compile until every place that renders a finding handles it —
   that is deliberate.
4. **Write the negative tests first.** What is the legitimate code shape that most
   resembles the bug, and does your rule stay quiet about it? If you cannot name one, the
   rule is probably not specific enough yet.

## Test conventions

Tests that need call-site attribution live under `com.example.*`, not under
`io.github.beagle4j.*`. Beagle filters its own frames out of every stack it walks, so a
test issuing queries from inside the library's own package would be attributed to nothing
and would quietly stop testing attribution.

Roughly half the existing tests assert that **nothing** is reported. Please keep that
ratio up.

## Style

Match the surrounding code. One thing worth knowing: comments here explain *why*, not
*what* — particularly where an obvious alternative was rejected. If you had to think about
something for more than a minute, the next person will too, and the comment is cheaper than
their minute.

## Benchmarks

```bash
mvn -Pbenchmark -DskipTests package
java -jar beagle-benchmark/target/benchmarks.jar
```

Close anything else that uses the CPU first. A benchmark run alongside a build measures
the build.
