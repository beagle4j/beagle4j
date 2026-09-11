# Benchmarks

Measured, not asserted. Reproduce with:

```bash
mvn -Pbenchmark -DskipTests package
java -jar beagle-benchmark/target/benchmarks.jar
```

**Environment.** Intel Core i9-14900HX (24 cores / 32 threads), Windows 11, Temurin JDK
21.0.11. JMH 1.37, one fork, 5×1s warmup, 8×1s measurement, average time per operation.
One machine, one run — treat the ratios as solid and the third significant figure as
decoration.

---

## JDBC overhead

One `SELECT` returning 50 rows, every row consumed, against H2 in memory.

| Case | µs/op | vs raw |
|---|---:|---:|
| Raw JDBC, no wrapper | 6.71 ± 0.32 | — |
| Wrapped, **not observing** | 7.12 ± 0.17 | **+0.41 µs** |
| Wrapped, observing (steady state) | 10.14 ± 1.20 | **+3.43 µs** |
| Wrapped, observing, stack cap removed | 13.47 ± 0.48 | +6.76 µs |

### Why H2 in memory

Because it is the harshest baseline available, not the most flattering one. A real
database answers over a socket in hundreds of microseconds to several milliseconds, which
would bury any overhead here and produce a reassuring percentage that means nothing. H2 in
memory answers in **6.7 µs**, so the instrumentation is a far larger fraction of each
operation than it could ever be in production.

### Read the absolute numbers, not the percentages

The overhead is a roughly **fixed ~3.4 µs per observed statement**. It is not a
percentage, and quoting it as one is only meaningful alongside the query it is a
percentage *of*:

| Query takes | Added | Overhead |
|---|---:|---:|
| 6.7 µs (H2 in memory) | 3.4 µs | 51% |
| 300 µs (fast indexed query, local Postgres) | 3.4 µs | 1.1% |
| 1 ms (typical) | 3.4 µs | 0.34% |
| 5 ms (a query worth reporting) | 3.4 µs | 0.07% |

The slower the query, the less Beagle matters — which is convenient, because slow queries
are the ones it exists to tell you about.

### The not-observing case

`+0.41 µs` across an operation that makes roughly 155 proxied JDBC calls —
`getConnection`, `prepareStatement`, `setInt`, `executeQuery`, 51 × `next()`, 100 ×
`getInt`/`getString`, three `close()` — works out at about **2.6 ns per intercepted call**.
That is reflective dispatch and nothing else; no session is open, so nothing is recorded.

This is the number that decides whether the dependency can stay in a production build with
`beagle.enabled=false`. It is small. It is not zero, and an earlier version of this README
claimed it was "one null check per statement", which was wrong.

### Caveats, in the direction that counts against us

- The steady-state case accumulates one record per statement for a full one-second
  iteration — hundreds of thousands of objects — producing GC pressure that a real request
  of a dozen statements never sees. It **overstates** the cost.
- That case is also the noisiest (±1.20 µs) and moved by ~1 µs between runs. Read it as
  "about 3 µs", not 3.43.

---

## Stack capture: why `StackWalker`

Attribution means finding one frame in a deep stack. The conventional idiom materialises
the whole thing.

| Stack depth | `StackWalker` | `new Throwable()` | Ratio |
|---|---:|---:|---:|
| 20 frames | 5.83 µs | 7.22 µs | 1.24× |
| 80 frames | 6.79 µs | 14.43 µs | **2.13×** |

The ratio is not the interesting part. **The scaling is.** Going from 20 to 80 frames costs
`StackWalker` 16% more and `Throwable` 100% more, because one of them stops at the frame it
wants and the other allocates a `StackTraceElement` for every frame regardless. Under a
JDBC call in a Spring application the stack is routinely 80+ frames of proxies, which is
the right-hand column.

**This is also why stack capture is capped.** A stack walk costs ~6 µs — comparable to an
entire in-memory query — so capturing one per statement would dominate everything else.
Beagle captures on a statement's first sighting and for at most 20 repeats, then stops.
The last row of the JDBC table is that cap removed, and the ~3 µs difference is what the
strategy buys on every statement after the twentieth.

*Caveat: these stacks are built by recursion, which the JIT may partially inline, so the
absolute figures are softer than the JDBC ones. Both variants pay that equally, so the
comparison holds.*

---

## SQL normalisation

| Case | ns/op |
|---|---:|
| Cache hit | **2.29 ± 0.19** |
| Cache miss (full scan) | 762.57 ± 81.30 |

Normalisation runs on every statement, so the hit is the number that matters: at ~2 ns it
is free. Misses are bounded by how many distinct statements an application has — a few
dozen, paid once each — and the memoisation cache holds 2048 entries before it stops
growing.

### Two bugs this benchmark had before it was trustworthy

Recorded because they are the reason to distrust a benchmark that has not been attacked:

1. **The cache was warmed in the wrong order.** The filler that fills the cache past its
   ceiling ran *before* the statement under test was admitted, so that statement was never
   cached. The "hit" benchmark was measuring a full re-scan of a long statement and
   reported **1397 ns/op** — the cache hit appearing to cost twice the miss. There is now
   an assertion in `@Setup` that fails the run rather than publishing it.

2. **The input was a constant.** After fixing (1) the hit measured **1.6 ns/op**, about
   four CPU cycles, which is not a hash lookup — it is the JIT hoisting a pure call with an
   unchanging argument out of the loop entirely. The benchmark now rotates through 16
   pre-cached statements. The honest figure is 2.29 ns.

The published numbers are the third attempt. The first two were wrong in opposite
directions and both looked plausible.
