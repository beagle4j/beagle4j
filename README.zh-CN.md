# Beagle4J 🐕

**专抓那些"代码能跑、测试能过、上线必炸"的数据库问题。**

[![Build](https://github.com/beagle4j/beagle4j/actions/workflows/ci.yml/badge.svg)](https://github.com/beagle4j/beagle4j/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://adoptium.net/)

[English](README.md) · 简体中文

本地跑十条测试数据一切正常，上线后接口从 80ms 变成 8 秒——大部分时候不是代码写错了，
而是**一次请求打了几百条 SQL**。Beagle 在开发和测试阶段就把这件事指出来，并且**精确到
是你哪一行代码干的**。

它也会告诉你，那个你以为存在的事务其实根本没开起来。

它监听的是 JDBC 层，所以 **Hibernate / JPA / MyBatis / MyBatis-Plus / jOOQ /
JdbcTemplate / 原生 JDBC 全都支持**——因为它们最后都要走这一层。

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

## 为什么做这个

Ruby 社区有 [Bullet](https://github.com/flyerhzm/bullet)，十五年、7000 star，专门干这件事。

Java 这边没有对应的东西。现有的几个项目都是"只能在单元测试里写断言"的小工具，
其中最完善的那个 star 数只有一百出头，而且 2024 年就停止维护了。

Beagle 想做 JVM 上的那个工具，并且补上 Rails 根本不需要操心、但 Java 后端天天踩的那些坑。

## 能查出什么

| 问题 | 状态 | 与同类的区别 |
|---|:---:|---|
| **N+1 查询** | ✅ | 用父查询返回行数做关联验证，不是简单数次数 |
| **重复的相同查询** | ✅ | 抓取绑定参数，能证明"这次查询的结果不可能变过" |
| **慢查询** | ✅ | 实测耗时，不是估算 |
| **压根没进事务的写操作** | ✅ | `@Transactional` 所有静默失效方式一网打尽，详见下文 |
| 无效的预加载 | 🚧 | 查出来的关联根本没人用 |
| 事务里发起远程调用 | 🚧 | 连接池被打爆的头号原因 |
| 未批量化的写循环 | 🚧 | 这是另一类 bug，会单独报，不跟 N+1 混为一谈 |

## 快速开始

### Spring Boot

加一个依赖，装完了。

```xml
<dependency>
  <groupId>io.github.beagle4j</groupId>
  <artifactId>beagle-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

容器里所有 `DataSource` 会被自动接管，每个 HTTP 请求自动成为一个观测单元。
建议加上这一行，它能让"这条 SQL 是你哪行代码发的"这个归因准确很多：

```yaml
beagle:
  application-package: com.acme
```

有发现时会打到日志里。二十个配置项在 IDE 里都有自动补全，常用的几个：

```yaml
beagle:
  enabled: true                 # false 则彻底关闭，连包装都不做
  n-plus-one-threshold: 3       # 重复多少次算可疑
  slow-query-threshold: 200ms   # 设为 0 关闭慢查询检测
  excluded-data-sources: []     # 不想被接管的 DataSource Bean 名
  web:
    excluded-paths: ["/actuator/**", "/static/**"]
```

> **一个必须说清楚的代价。** Beagle 会把 `DataSource` Bean 替换成包装类。
> 注入接口类型的代码不受影响（绝大多数代码都是这样），但如果你的代码注入的是
> **具体类型**（比如为了读连接池指标而注入 `HikariDataSource`），就会找不到 Bean。
> `unwrap()` 仍然能拿到原对象，也可以用 `beagle.excluded-data-sources` 把指定 Bean 排除掉。

### 非 Spring 环境

`beagle-core` 和 `beagle-jdbc` **不依赖 Spring**，所以 MyBatis 裸用、Quarkus、
Micronaut、测试代码里、纯 JDBC，同一套引擎都能用：

```java
DataSource observed = BeagleDataSource.wrap(myDataSource);

BeagleRuntime beagle = new BeagleRuntime(
        BeagleConfig.builder().applicationPackage("com.acme").build());

DetectionReport report = beagle.observe("GET /authors", () -> service.listAuthors());

System.out.println(new ConsoleReporter().render(report));
```

没有开启会话时不记录任何东西。包装层本身仍有开销，具体数字见 [性能](#性能) 一节——
很小，但**不是零**。

## 为什么"数次数"是不够的

这个领域大部分工具的判断逻辑是"同一条 SQL 执行超过 N 次就报"。这也正是它们最后
都被关掉的原因——批量导入任务本来就要跑一万次 INSERT，定时任务本来就要轮询同一条
SELECT。工具把这些当 bug 报，只会训练出"看到告警就忽略"的习惯，而被忽略的工具等于不存在。

**真正让 N+1 成为 N+1 的，不是"重复"，而是"因果"**：重复多少次，是由前面那条查询
返回了多少行决定的。12 行数据进去，12 条查询出来。

所以 Beagle 从第一次重复往前找，定位到引发这批查询的那条父查询，再去比对
它的返回行数能不能解释这个重复次数：

```
  select id, name from author order by id          → 返回 12 行
  select id, title from book where author_id = ?   × 执行 12 次
                                                     ↑
                        12 行进、12 次查询出 —— 是数据的形状决定了流量的形状
```

对得上的时候，这就不是猜测了，会标记为 **confirmed（已确证）**。对不上的仍然会报，
但标记为 **likely（疑似）**——它常常也是真的 N+1，只是父数据来自缓存。
**你可以只用 confirmed 那部分去卡 CI，永远不会遇到误杀。**

另外三条规则专门用来压误报：

- **写操作永远不会被判成 N+1。** 循环 INSERT 确实该批量化，但那是另一个 bug、另一种改法，
  而且绝大多数时候是故意写成这样的。
- **参数完全相同的重复归另一个检测器管。** 同一条语句、同一批参数、跑了 12 次，
  那是纯浪费而不是 N+1——只报一次，报在正确的名字下。
- **找父查询时会跳过"兄弟"查询。** 当循环体里每行发两条查询时，第二条前面那条是第一个
  子查询而不是父查询，不跳过就会拿 12 次重复去比对一个只返回 3 行的兄弟，把真问题降级掉。

## 查症状，不查注解

Spring 的声明式事务有好几种静默失效的方式。最有名的是**自调用**——在同一个类里调用
`@Transactional` 方法会绕过代理；此外还有注解加在 `private` 或 `final` 方法上、
对象压根不是 Spring Bean、以及单纯忘了写。

静态分析要一个一个去追，而且**两个方向都会错**：它看不出你是故意用
`AopContext.currentProxy()` 绕开的（误报），也看不出运行时被其他机制代理的 Bean（漏报）。

**Beagle 完全不看注解，它检查事务到底有没有真的发生。** 上面所有失效方式，
症状都是同一个——SQL 在 autocommit 开着的情况下执行了。而**症状才是真正让你丢数据的东西**。
抓症状等于一次抓住所有原因，包括那些还没人给它写过 lint 规则的：

```
  WRITES OUTSIDE A TRANSACTION                              critical · confirmed
  2 writes that could be left half-applied

  first     OrderService#placeOrder  (OrderService.java:58)
  writes    insert into orders (id, customer_id, total) values (?, ?, ?)
            update inventory set stock = stock - ? where sku = ?
```

触发条件是：一个工作单元内，**两条或以上使用不同语句模板**的写操作，全部在事务外执行。

"不同语句模板"这个限定很关键——循环插一千行、autocommit 开着，那是**同一条语句重复**，
属于"该不该批量化"的问题，不是"工作单元丢了"。把它也报出来，
这条规则就会被关掉，然后它本来要抓的那个问题就再也抓不到了。

## 性能

栈回溯是这类工具最贵的部分，所以 Beagle 只在"能改变结论"的地方才付这个钱：

1. 某条 SQL 模板**第一次**出现时——这个数量等于应用里不同 SQL 的条数，几十条，且只付一次；
2. 出现重复时，每个模板最多采样 20 次——重复正是我们要抓的目标，值得付钱；
3. 之后不再采样。批量任务跑同一条 SQL 十万次，只付 20 次栈回溯的代价，不是十万次。

用的是 `StackWalker` 而不是 `new Throwable().getStackTrace()`：后者会把整个调用栈
一次性实例化，而 Spring 应用里一条 JDBC 调用下面通常有 80 多层栈，绝大部分是代理。

### 实测数据

一条返回 50 行的 `SELECT`，逐行读完，打在 **H2 内存库**上——**故意选的最不利基准**，
因为真实数据库的网络往返会把这点开销完全淹没：

| 场景 | µs/op | 增量 |
|---|---:|---:|
| 原生 JDBC，无包装 | 6.71 | — |
| 已包装，未观测 | 7.12 | **+0.41 µs**（每次被拦截的 JDBC 调用约 2.6 ns）|
| 已包装，正在观测 | 10.14 | **每条 SQL 固定约 +3.4 µs** |

**关键是看绝对值，不是百分比。** 开销基本固定在每条语句 3.4 µs，
所以百分比完全取决于分母是什么：占 H2 内存查询的 51%，占 1 毫秒查询的 0.34%，
占那条你真正想知道的 5 毫秒慢查询的 0.07%。

设计决策也经受住了测量：栈深从 20 到 80，`StackWalker` 只多 16%，
`new Throwable()` 多 100%；SQL 归一化的缓存命中是 2.3 纳秒。

**[完整数据、测量方法，以及这套 benchmark 在可信之前踩过的两个坑 →](docs/BENCHMARKS.md)**

## 设计文档

为什么用动态代理而不是 Java Agent、为什么手写 SQL 扫描器而不是用正则或完整解析器、
为什么用 `ThreadLocal` 而不是 `InheritableThreadLocal`——这些决策的完整推理在
[`docs/DESIGN-NOTES.md`](docs/DESIGN-NOTES.md)。

## 项目状态

**早期阶段，0.1.0 版本，尚未发布到 Maven Central。** 检测引擎、JDBC 拦截层、
控制台报告器和 Spring Boot Starter 都已可用，有 35 个测试覆盖，包含针对真实数据库的
端到端检测。接下来是 JMH 基准测试、剩余的检测规则和 HTML 报告。

欢迎提 issue 和 PR，**尤其欢迎误报的反馈**——对这类工具来说，那是最要命的 bug。

## 本地构建

```bash
git clone https://github.com/beagle4j/beagle4j.git
cd beagle4j
mvn test
```

需要 JDK 17 或更高版本。

## 开源协议

[Apache 2.0](LICENSE)
