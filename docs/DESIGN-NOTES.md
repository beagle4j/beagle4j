# Design notes

Why Beagle4J is built the way it is. Each section states the decision, the alternatives
that were rejected, and what would have to change for the decision to be revisited.

---

## 1. Dynamic proxies, not a Java agent

**Decision.** JDBC is instrumented by wrapping the `DataSource` in a JDK dynamic proxy,
which hands out proxied `Connection`s, which hand out proxied `Statement`s and
`ResultSet`s.

**Why not bytecode instrumentation?** An agent is the right tool when the thing you need
to observe is not expressed as an interface — and that is exactly why the roadmap has one
for outbound HTTP clients. JDBC is the opposite case. The entire API is defined in terms
of `Connection`, `Statement` and `ResultSet` interfaces, so a proxy observes everything an
agent would observe, with three advantages:

- **Compatibility.** HikariCP, Druid, Tomcat JDBC and every vendor driver already hand out
  their own wrappers. Adding one more layer of the same kind is something they are all
  built to tolerate. Rewriting their bytecode from underneath them is how subtle breakage
  starts.
- **Adoption.** No `-javaagent` flag means adding a dependency rather than changing how
  the application is launched. For a tool nobody has heard of, that difference is most of
  whether it gets tried at all.
- **No class-loader ordering problems**, and no dependency on `java.lang.instrument`.

**Cost.** Reflective dispatch on every JDBC call. Every one of those calls is accompanied
by a database round trip, so the relative cost is noise.

**What would change this.** Wanting to observe something that is not interface-based —
`RestTemplate`, a gRPC stub, a Redis client. That is a separate module, not a replacement
for this one.

---

## 2. Row correlation, not repeat counting

**Decision.** A burst of repeated statements is only reported as a *confirmed* N+1 when
the number of repeats is explained by the row count of the query that preceded it.

**The problem with counting.** "The same statement ran more than N times" is what most
tools in this space check, and it is why most of them end up muted. A batch importer
legitimately runs one INSERT ten thousand times; a poller legitimately re-runs one SELECT.
Reporting those trains a developer to ignore the tool, and an ignored tool catches nothing.

**The insight.** What makes an N+1 an N+1 is not repetition, it is *causation*: the size
of a result set that was just read determines how many follow-up queries are issued.
Twelve rows in, twelve queries out. So the detector searches backwards from the first
repeat for the preceding query and compares its `rowsRead` against the repeat count. A
match means the shape of the data caused the shape of the traffic, which is the definition
of the bug rather than a proxy for it.

**Honesty about the rest.** Uncorrelated repeats are still reported, as *likely* rather
than *confirmed*. Publishing the distinction is what lets a team gate CI on the confirmed
findings alone and never get a flaky failure — which is worth more than the extra recall
would have been.

**Three supporting rules**, each of which removes a category of false positive:

| Rule | Without it |
|---|---|
| Writes are never reported as N+1 | Every deliberate batch-insert loop is a false positive |
| Identical repeats belong to `RepeatedQueryDetector` | One problem reported twice under two names |
| The parent search steps over other repeated groups | A loop issuing two queries per row compares 12 repeats against a 3-row sibling and downgrades a real finding |

---

## 3. Counting rows by counting `next()`

**Decision.** `ResultSet.next()` is intercepted and successful calls are counted.

**Why.** Row correlation needs the parent's row count, and JDBC will not tell you. The
driver knows what it has buffered, `ResultSet` has no API for it, and for a forward-only
cursor the number genuinely is not known until the cursor is exhausted.

It also measures the *right* number. What drives an N+1 is how many rows the application
**iterated**, not how many the query could have returned — and those differ every time a
loop breaks early.

**Limitation.** Scrollable result sets that revisit rows are not counted correctly. They
are rare in the frameworks this targets, and a cursor that can move backwards cannot be
turned into a row count by counting moves.

---

## 4. Capturing bind parameters

**Decision.** `setXxx(int, ...)` calls are intercepted and the values rendered to short
strings immediately.

**Why at all.** A `PreparedStatement` keeps its parameters out of the SQL text. Every
execution of `where id = ?` carries an identical string, so without the bound values there
is no way to distinguish an N+1 (one statement, fifty different ids) from genuinely wasted
work (one statement, the same id fifty times). Those are different bugs with different
fixes.

**Why rendered eagerly rather than retained.** A bound parameter can be a detached entity,
a large byte array, or anything else with a long tail of references behind it. Holding one
for the duration of a session would turn a diagnostic tool into a memory leak. Values are
truncated to 64 characters and capped at 64 parameters.

---

## 5. Lazy, capped stack capture

**Decision.** Stacks are walked (a) the first time a statement template is seen, and
(b) on repeats, up to 20 samples per template. Never after that.

**Why.** Stack capture is the expensive part of a tool like this, and capturing on every
query would make Beagle too costly to leave switched on. The rule spends the budget only
where it can change a conclusion:

- First sightings are bounded by how many distinct statements the application has — a few
  dozen, paid once.
- Repeats are the thing being hunted, so they are worth paying for.
- Beyond 20 samples there is no further evidence to gain. A batch job running one
  statement 100,000 times pays for 20 stack walks.

Executions past the cap inherit the call site already established for that template. That
is an inference, not an observation; it is sound in the case that matters — repeats of one
statement from one loop — and the sample count is retained so a report can say so.

**`StackWalker`, not `new Throwable()`.** Filling in a throwable materialises the whole
stack eagerly, allocating a `StackTraceElement` per frame. Under a JDBC call in a Spring
application that stack is routinely 80+ frames, almost all proxies. `StackWalker` streams
lazily and stops at the first frame that matters.

**Attribution rule.** Take the *deepest* frame that is not infrastructure. With Spring
Data the repository is itself a generated proxy, so this naturally lands on the service
method that called it — which is where the loop lives.

---

## 6. A hand-written SQL scanner

**Decision.** Statements are reduced to templates by a character-level state machine.

**Why not a regex.** SQL string literals can contain anything, including comment markers,
semicolons and escaped quotes. A regex that strips `'...'` mangles
`where note = 'it''s fine -- really'`, and a normaliser that mangles input produces wrong
groupings, which produces wrong findings — silently.

**Why not a real parser.** Parsing every SQL dialect correctly is a project in itself, and
nothing downstream needs a syntax tree. It needs stable grouping.

**What it does.** Literals and comments out, whitespace collapsed, `IN (?, ?, ?)` and
multi-row `VALUES` collapsed so that a MyBatis `foreach` over 3 ids and one over 300 share
a template, everything lower-cased. Results are memoised in a bounded cache, so an
application generating unique SQL forever cannot leak.

---

## 7. `ThreadLocal`, not `InheritableThreadLocal`

**Decision.** The current session is held in a plain `ThreadLocal`, with explicit
propagation via `SessionContext.wrap(Runnable)`.

**Why not inheritable.** It looks convenient — child threads would pick up the session for
free — but the threads in question are almost always pooled. A pooled thread inherits the
session belonging to whichever request happened to create it and then keeps that stale
reference for the lifetime of the pool. The result is queries attributed to the wrong
request, and a session that is never collected. Explicit propagation is more typing and is
correct.

**Future.** `ScopedValue` (JEP 446) is the better answer, especially under virtual
threads, but it is still preview on the Java 17 baseline. `SessionContext` exists as an
indirection so that swap can happen without touching a single caller.

---

## 8. Confidence as a first-class field

**Decision.** Every finding publishes how sure the tool is, separately from how severe the
problem is.

**Why.** A detector that pretends to certainty it does not have teaches developers to
distrust the whole tool. Saying "I am fairly sure, and here is the evidence" keeps the
low-confidence findings useful instead of making them poison. It also makes
`DetectionReport.actionable()` possible: a deliberately narrow set — high confidence,
non-informational — that a build can be gated on without becoming flaky.

Severity and confidence are genuinely orthogonal. A slow query is *measured*, so it is
high confidence, but whether it is a real problem depends on whether you are looking at
production data or ten rows on a laptop — so its severity stays at `WARNING`.

---

## 9. Java 17 baseline, Spring Boot 3.5 compile target

**Decision.** The library targets Java 17. The Spring Boot starter will be compiled
against Spring Boot 3.5 with `provided` scope.

**Why not 21.** Java 17 is where the installed base is. A diagnostic tool is adopted by
teams with existing problems, not greenfield projects, and those teams are the least
likely to be on the newest LTS.

**Why the oldest supported Spring line.** Compiling against 3.5 with `provided` scope
produces one artifact that runs on both 3.x and 4.x, as long as only stable APIs are used.
Compiling against 4.x would silently drop every 3.x user.

**Why core has no Spring dependency at all.** `beagle-core` and `beagle-jdbc` depend on
nothing but the JDK and the SLF4J API. That keeps the tool usable from MyBatis standalone,
Quarkus, Micronaut or plain JDBC — a larger audience than Spring alone, for no extra work.

---

## 10. Failure must never propagate

**Decision.** Every instrumentation path and every detector is wrapped so that an
exception inside Beagle cannot reach the application.

**Why.** Observability that can break the thing it observes is not worth having. A
detector that throws is logged and skipped; a recording failure is swallowed and the query
runs regardless. The request matters, the diagnostics do not.

---

## 11. Detecting the symptom, not the annotation

**Decision.** There is no `@Transactional` self-invocation detector. There is a rule that
reports several different writes running in one unit of work with autocommit on.

**Why the reframing is an improvement, not a compromise.** The obvious feature request is
"catch self-invocation", and the obvious implementation needs bytecode: a self-call does
not pass through the proxy, so no AOP advice can observe it. That would mean an agent,
and an agent means a launch-flag change — the thing section 1 exists to avoid.

But self-invocation is only one of the ways a Spring transaction silently fails. The
annotation on a `private` or `final` method fails. A class that was never a Spring bean
fails. Forgetting the annotation fails. A linter has to model each of these separately and
gets both directions wrong — it cannot see a self-call deliberately routed through
`AopContext.currentProxy()`, and it cannot see a bean proxied at runtime by a mechanism it
does not know about.

Every one of those causes produces the same observable symptom: statements executing with
autocommit on. The symptom is also the thing that actually loses data. So the rule checks
for the symptom, which catches every cause at once — including the ones nobody has written
a lint rule for yet — and needs no bytecode to do it.

**The discriminator.** Two or more writes using *different* statement templates. One
template repeated is a batch loop with autocommit on: a batching question, not a lost unit
of work, and reporting it would cost the rule the credibility it needs for the case it
exists to catch.

**Why the transaction flag is read at execution time.** Statements are routinely prepared
on the other side of a transaction boundary from where they run — Spring opens the
transaction around the service method while the ORM prepares statements inside it, and a
cached statement can outlive several transactions. Sampling `autoCommit` when the
statement is created puts writes on the wrong side of the boundary and the rule silently
stops working. It is therefore passed as a `BooleanSupplier` and evaluated on execute.

**Limitation.** A pool configured with `autoCommit=false` globally never shows the
symptom, so the rule reports nothing. That is a false negative, which is the safe
direction to fail in.
