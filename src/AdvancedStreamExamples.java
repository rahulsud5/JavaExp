import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * ============================================================================
 *  20 ADVANCED JAVA STREAM EXAMPLES  (Java 21)
 * ============================================================================
 *
 *  These examples go beyond filter/map/collect and cover:
 *    - Collectors: groupingBy, partitioningBy, toMap, teeing, summarizing,
 *      collectingAndThen, and downstream collectors (mapping, filtering,
 *      flatMapping, counting, maxBy)
 *    - Newer Stream APIs: mapMulti (16), takeWhile/dropWhile (9),
 *      iterate-with-predicate (9), toList (16)
 *    - Java 21 language features used with streams: records, sealed
 *      interfaces, pattern matching for switch, record patterns, and
 *      SequencedCollection (getFirst / getLast / reversed)
 *    - Custom collectors, parallel streams, laziness and short-circuiting,
 *      and practical recipes (top-N, chunking, sliding windows)
 *
 *  HOW TO RUN:
 *      javac AdvancedStreamExamples.java
 *      java AdvancedStreamExamples
 *
 *  Each example calls check(...) which prints the result AND verifies it,
 *  so running the file is also a self-test.
 * ============================================================================
 */
public class AdvancedStreamExamples {

    // ------------------------------------------------------------------------
    // Shared sample data. A record (Java 16+) is a concise, immutable data
    // carrier: the compiler generates the constructor, accessors (name(),
    // dept(), ...), equals, hashCode and toString.
    // ------------------------------------------------------------------------
    record Employee(String name, String dept, double salary, int age, List<String> skills) {}

    static final List<Employee> EMPLOYEES = List.of(
            new Employee("Alice", "Engineering", 120_000, 34, List.of("Java", "SQL")),
            new Employee("Bob",   "Engineering",  95_000, 28, List.of("Java", "Kotlin")),
            new Employee("Carol", "Sales",        70_000, 45, List.of("Negotiation")),
            new Employee("Dan",   "Sales",        65_000, 23, List.of("CRM", "Negotiation")),
            new Employee("Eve",   "Marketing",    80_000, 31, List.of("SEO", "Writing")),
            new Employee("Frank", "Engineering", 135_000, 50, List.of("Java", "Go", "SQL")),
            new Employee("Grace", "Marketing",    72_000, 26, List.of("Writing"))
    );

    // ------------------------------------------------------------------------
    // Tiny test helper
    // ------------------------------------------------------------------------
    private static int passed = 0;

    static <T> void check(String label, T actual, T expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(label + " -> expected " + expected + " but got " + actual);
        }
        passed++;
        System.out.printf("   %-40s %s%n", label, actual);
    }

    static void header(int n, String title) {
        System.out.printf("%n[%02d] %s%n", n, title);
    }

    public static void main(String[] args) {
        example01_groupingBy();
        example02_groupingByCountingIntoTreeMap();
        example03_partitioningBy();
        example04_toMapWithMergeFunction();
        example05_flatMap();
        example06_mapMulti();
        example07_takeWhileDropWhile();
        example08_iterateWithPredicateAndRecordState();
        example09_teeing();
        example10_summaryStatistics();
        example11_collectingAndThenMaxPerGroup();
        example12_downstreamMappingFilteringFlatMapping();
        example13_sealedInterfacesAndPatternSwitch();
        example14_recordPatterns();
        example15_customCollector();
        example16_parallelStreams();
        example17_lazinessAndShortCircuiting();
        example18_wordFrequencyTopN();
        example19_chunkingAndSlidingWindows();
        example20_multiLevelGroupingAndSequencedCollections();

        System.out.printf("%nAll %d checks passed.%n", passed);
    }

    // ========================================================================
    // 01. groupingBy
    // ------------------------------------------------------------------------
    // Feature: Collectors.groupingBy(classifier) builds a Map<K, List<T>> where
    // K is whatever the classifier function returns.
    //
    // The 3-argument form groupingBy(classifier, mapFactory, downstream) lets
    // you choose the Map type (TreeMap = sorted keys) and what to do with each
    // group (here: mapping each Employee to its name).
    // ========================================================================
    static void example01_groupingBy() {
        header(1, "groupingBy - bucket elements by a key");

        // Simple form: Map<String, List<Employee>>
        Map<String, List<Employee>> byDept = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(Employee::dept));
        check("Engineering head-count", byDept.get("Engineering").size(), 3);

        // Full form: sorted keys + only keep the names
        Map<String, List<String>> namesByDept = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(
                        Employee::dept,                                     // key
                        TreeMap::new,                                       // map implementation
                        Collectors.mapping(Employee::name, Collectors.toList()))); // value
        check("names by dept", namesByDept, Map.of(
                "Engineering", List.of("Alice", "Bob", "Frank"),
                "Marketing", List.of("Eve", "Grace"),
                "Sales", List.of("Carol", "Dan")));
        check("TreeMap keeps keys sorted", List.copyOf(namesByDept.keySet()),
                List.of("Engineering", "Marketing", "Sales"));
    }

    // ========================================================================
    // 02. groupingBy + counting / averaging / summing
    // ------------------------------------------------------------------------
    // Feature: DOWNSTREAM collectors. The second argument to groupingBy says
    // how to reduce each group. Instead of a List you can get a count, a sum,
    // an average, etc. This is SQL's "GROUP BY dept" + COUNT(*) / AVG(...).
    // ========================================================================
    static void example02_groupingByCountingIntoTreeMap() {
        header(2, "groupingBy + counting / averaging (SQL GROUP BY)");

        Map<String, Long> countByDept = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(Employee::dept, TreeMap::new, Collectors.counting()));
        check("count by dept", countByDept, Map.of("Engineering", 3L, "Marketing", 2L, "Sales", 2L));

        Map<String, Double> avgSalaryByDept = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(Employee::dept, Collectors.averagingDouble(Employee::salary)));
        check("avg salary Sales", avgSalaryByDept.get("Sales"), 67_500.0);
        check("avg salary Marketing", avgSalaryByDept.get("Marketing"), 76_000.0);
    }

    // ========================================================================
    // 03. partitioningBy
    // ------------------------------------------------------------------------
    // Feature: partitioningBy(predicate) is a special groupingBy that always
    // returns a Map<Boolean, ...> with BOTH keys true and false present
    // (even if one partition is empty). Good for "split into two buckets".
    // ========================================================================
    static void example03_partitioningBy() {
        header(3, "partitioningBy - split into true/false buckets");

        Map<Boolean, List<String>> highEarners = EMPLOYEES.stream()
                .collect(Collectors.partitioningBy(
                        e -> e.salary() >= 80_000,
                        Collectors.mapping(Employee::name, Collectors.toList())));
        check(">= 80k", highEarners.get(true), List.of("Alice", "Bob", "Eve", "Frank"));
        check("< 80k", highEarners.get(false), List.of("Carol", "Dan", "Grace"));

        // Both keys exist even when a partition is empty:
        Map<Boolean, Long> over200k = EMPLOYEES.stream()
                .collect(Collectors.partitioningBy(e -> e.salary() > 200_000, Collectors.counting()));
        check("empty partition still present", over200k, Map.of(true, 0L, false, 7L));
    }

    // ========================================================================
    // 04. toMap with merge function and map supplier
    // ------------------------------------------------------------------------
    // Feature: Collectors.toMap(keyFn, valueFn, mergeFn, mapSupplier).
    //   - Without a merge function, DUPLICATE KEYS throw IllegalStateException.
    //   - mergeFn decides how to combine values for the same key.
    //   - mapSupplier picks the map type (LinkedHashMap = insertion order).
    // ========================================================================
    static void example04_toMapWithMergeFunction() {
        header(4, "toMap - merge duplicates, choose map type");

        // Duplicate keys WITHOUT a merge function -> exception
        boolean threw = false;
        try {
            EMPLOYEES.stream().collect(Collectors.toMap(Employee::dept, Employee::salary));
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("duplicate key throws", threw, true);

        // With a merge function: total payroll per department, in first-seen order
        Map<String, Double> payroll = EMPLOYEES.stream()
                .collect(Collectors.toMap(
                        Employee::dept,
                        Employee::salary,
                        Double::sum,               // merge: add salaries of same dept
                        LinkedHashMap::new));      // keep encounter order of keys
        check("payroll key order", List.copyOf(payroll.keySet()),
                List.of("Engineering", "Sales", "Marketing"));
        check("payroll values", List.copyOf(payroll.values()),
                List.of(350_000.0, 135_000.0, 152_000.0));

        // Handy idiom: index records by a unique key
        Map<String, Employee> byName = EMPLOYEES.stream()
                .collect(Collectors.toMap(Employee::name, Function.identity()));
        check("lookup by name", byName.get("Eve").dept(), "Marketing");
    }

    // ========================================================================
    // 05. flatMap
    // ------------------------------------------------------------------------
    // Feature: flatMap(T -> Stream<R>) maps each element to a STREAM and then
    // flattens all those streams into one. Use it for one-to-many mappings
    // and for flattening nested collections.
    // ========================================================================
    static void example05_flatMap() {
        header(5, "flatMap - flatten one-to-many");

        List<List<Integer>> nested = List.of(List.of(1, 2), List.of(3), List.of(), List.of(4, 5, 6));
        List<Integer> flat = nested.stream()
                .flatMap(List::stream)       // each inner list -> its stream
                .toList();
        check("flatten nested lists", flat, List.of(1, 2, 3, 4, 5, 6));

        // All distinct skills across the company, sorted
        List<String> allSkills = EMPLOYEES.stream()
                .flatMap(e -> e.skills().stream())
                .distinct()
                .sorted()
                .toList();
        check("all distinct skills", allSkills,
                List.of("CRM", "Go", "Java", "Kotlin", "Negotiation", "SEO", "SQL", "Writing"));

        // Split sentences into words
        List<String> words = Stream.of("hello world", "streams are fun")
                .flatMap(s -> Arrays.stream(s.split(" ")))
                .toList();
        check("sentences -> words", words, List.of("hello", "world", "streams", "are", "fun"));
    }

    // ========================================================================
    // 06. mapMulti  (Java 16+)
    // ------------------------------------------------------------------------
    // Feature: mapMulti((element, sink) -> ...) is an imperative alternative to
    // flatMap. Instead of returning a Stream, you push 0..N values into `sink`.
    // It avoids creating a small Stream per element, and it makes
    // "filter + cast + map" in a single step very readable.
    // NOTE: the type witness <Integer> is usually needed so Java can infer R.
    // ========================================================================
    static void example06_mapMulti() {
        header(6, "mapMulti - push 0..N results per element (Java 16)");

        // Filter by type AND transform, in one step, using pattern matching instanceof
        List<Object> mixed = List.of(1, "two", 3, 4.0, 5, "six");
        List<Integer> intsTimesTen = mixed.stream()
                .<Integer>mapMulti((obj, sink) -> {
                    if (obj instanceof Integer i) {   // pattern matching for instanceof
                        sink.accept(i * 10);          // emit one value
                    }                                 // emit nothing for non-Integers
                })
                .toList();
        check("only Integers, x10", intsTimesTen, List.of(10, 30, 50));

        // One-to-many: emit "name:skill" for every skill of every engineer
        List<String> pairs = EMPLOYEES.stream()
                .filter(e -> e.dept().equals("Engineering"))
                .<String>mapMulti((e, sink) -> e.skills().forEach(s -> sink.accept(e.name() + ":" + s)))
                .toList();
        check("engineer skill pairs", pairs, List.of(
                "Alice:Java", "Alice:SQL", "Bob:Java", "Bob:Kotlin", "Frank:Java", "Frank:Go", "Frank:SQL"));
    }

    // ========================================================================
    // 07. takeWhile / dropWhile  (Java 9+)
    // ------------------------------------------------------------------------
    // Feature: predicate-based slicing on ORDERED streams.
    //   - takeWhile(p): take elements while p is true, STOP at the first false.
    //   - dropWhile(p): skip elements while p is true, keep everything after.
    // Unlike filter, they don't look at elements after the first failure.
    // Works on infinite streams too, where filter would never finish.
    // ========================================================================
    static void example07_takeWhileDropWhile() {
        header(7, "takeWhile / dropWhile - predicate slicing (Java 9)");

        List<Integer> nums = List.of(1, 2, 3, 10, 4, 5);

        check("takeWhile(n < 5)", nums.stream().takeWhile(n -> n < 5).toList(), List.of(1, 2, 3));
        check("dropWhile(n < 5)", nums.stream().dropWhile(n -> n < 5).toList(), List.of(10, 4, 5));
        check("filter(n < 5) for contrast", nums.stream().filter(n -> n < 5).toList(), List.of(1, 2, 3, 4));

        // Practical: skip header lines of a "file", stop at a footer marker
        List<String> lines = List.of("# header", "# meta", "row1", "row2", "END", "garbage");
        List<String> body = lines.stream()
                .dropWhile(l -> l.startsWith("#"))
                .takeWhile(l -> !l.equals("END"))
                .toList();
        check("body between header and END", body, List.of("row1", "row2"));

        // takeWhile safely terminates an INFINITE stream
        List<Integer> powersOf2 = Stream.iterate(1, n -> n * 2).takeWhile(n -> n < 100).toList();
        check("powers of 2 below 100", powersOf2, List.of(1, 2, 4, 8, 16, 32, 64));
    }

    // ========================================================================
    // 08. Stream.iterate with a predicate, and stateful generation via records
    // ------------------------------------------------------------------------
    // Feature: Stream.iterate(seed, hasNext, next)  (Java 9+) works like a
    // for-loop: for (x = seed; hasNext(x); x = next(x)). It is finite.
    // The 2-argument iterate(seed, next) is INFINITE and needs limit/takeWhile.
    // A small record is a clean way to carry multi-value state between steps
    // (here: the Fibonacci pair (a, b)).
    // ========================================================================
    record FibPair(long a, long b) {
        FibPair next() { return new FibPair(b, a + b); }
    }

    static void example08_iterateWithPredicateAndRecordState() {
        header(8, "iterate(seed, hasNext, next) + record state");

        List<Integer> powersOf3 = Stream.iterate(1, n -> n <= 1000, n -> n * 3).toList();
        check("powers of 3 <= 1000", powersOf3, List.of(1, 3, 9, 27, 81, 243, 729));

        List<Long> fib = Stream.iterate(new FibPair(0, 1), FibPair::next)   // infinite
                .limit(10)                                                   // make it finite
                .map(FibPair::a)
                .toList();
        check("first 10 Fibonacci", fib, List.of(0L, 1L, 1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L));
    }

    // ========================================================================
    // 09. Collectors.teeing  (Java 12+)
    // ------------------------------------------------------------------------
    // Feature: teeing(collector1, collector2, merger) sends every element to
    // TWO collectors at once and merges their results. You get two aggregates
    // in a SINGLE pass over the data (no need to stream twice).
    // ========================================================================
    record SalaryRange(double min, double max) {}

    static void example09_teeing() {
        header(9, "Collectors.teeing - two results in one pass (Java 12)");

        SalaryRange range = EMPLOYEES.stream()
                .map(Employee::salary)
                .collect(Collectors.teeing(
                        // The explicit type witness <Double> is required here: inference
                        // can't flow through teeing -> minBy -> naturalOrder on its own.
                        Collectors.minBy(Comparator.<Double>naturalOrder()),
                        Collectors.maxBy(Comparator.<Double>naturalOrder()),
                        (min, max) -> new SalaryRange(min.orElseThrow(), max.orElseThrow())));
        check("salary range", range, new SalaryRange(65_000, 135_000));

        // Percentage of employees who know Java, in a single pass
        double javaPct = EMPLOYEES.stream()
                .collect(Collectors.teeing(
                        Collectors.filtering(e -> e.skills().contains("Java"), Collectors.counting()),
                        Collectors.counting(),
                        (java, total) -> Math.round(java * 1000.0 / total) / 10.0));
        check("% of employees knowing Java", javaPct, 42.9);
    }

    // ========================================================================
    // 10. Summary statistics
    // ------------------------------------------------------------------------
    // Feature: summarizingDouble / summaryStatistics() compute count, sum,
    // min, max and average in ONE pass and return them in one object
    // (DoubleSummaryStatistics / IntSummaryStatistics / LongSummaryStatistics).
    // Combine with groupingBy for per-group stats.
    // ========================================================================
    static void example10_summaryStatistics() {
        header(10, "Summary statistics in one pass");

        DoubleSummaryStatistics stats = EMPLOYEES.stream()
                .collect(Collectors.summarizingDouble(Employee::salary));
        check("count", stats.getCount(), 7L);
        check("sum", stats.getSum(), 637_000.0);
        check("min", stats.getMin(), 65_000.0);
        check("max", stats.getMax(), 135_000.0);
        check("average", stats.getAverage(), 91_000.0);

        // Primitive-stream version, per group
        Map<String, IntSummaryStatistics> ageStatsByDept = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(Employee::dept, Collectors.summarizingInt(Employee::age)));
        check("Engineering max age", ageStatsByDept.get("Engineering").getMax(), 50);
    }

    // ========================================================================
    // 11. collectingAndThen + maxBy per group
    // ------------------------------------------------------------------------
    // Feature: collectingAndThen(collector, finisher) runs a final function on
    // a collector's result. Common uses:
    //   - unwrap the Optional from maxBy/minBy inside a groupingBy
    //   - make a collected list unmodifiable
    // ========================================================================
    static void example11_collectingAndThenMaxPerGroup() {
        header(11, "collectingAndThen - post-process a collector");

        // Highest-paid person per department. maxBy returns Optional<Employee>;
        // collectingAndThen unwraps it and extracts the name.
        Map<String, String> topEarner = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(
                        Employee::dept,
                        TreeMap::new,
                        Collectors.collectingAndThen(
                                Collectors.maxBy(Comparator.comparingDouble(Employee::salary)),
                                opt -> opt.map(Employee::name).orElseThrow())));
        check("top earner per dept", topEarner,
                Map.of("Engineering", "Frank", "Marketing", "Eve", "Sales", "Carol"));

        // Wrap a result as unmodifiable
        List<String> frozen = EMPLOYEES.stream()
                .map(Employee::name)
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
        boolean threw = false;
        try { frozen.add("Zed"); } catch (UnsupportedOperationException e) { threw = true; }
        check("unmodifiable result", threw, true);
    }

    // ========================================================================
    // 12. Downstream mapping / filtering / flatMapping  (filtering & flatMapping: Java 9+)
    // ------------------------------------------------------------------------
    // Feature: the stream operations map / filter / flatMap all have collector
    // equivalents that work INSIDE a group.
    //
    // Why Collectors.filtering instead of stream.filter? Filtering before
    // grouping removes groups that end up empty. Filtering inside the
    // grouping KEEPS every key (with an empty list).
    // ========================================================================
    static void example12_downstreamMappingFilteringFlatMapping() {
        header(12, "Downstream mapping / filtering / flatMapping");

        // Employees over 40, per dept. filtering keeps depts that have none.
        Map<String, List<String>> over40 = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(
                        Employee::dept,
                        TreeMap::new,
                        Collectors.filtering(e -> e.age() > 40,
                                Collectors.mapping(Employee::name, Collectors.toList()))));
        check("over 40 (all keys kept)", over40, Map.of(
                "Engineering", List.of("Frank"),
                "Marketing", List.of(),          // <- still present, just empty
                "Sales", List.of("Carol")));

        // Contrast: stream.filter first -> Marketing disappears entirely
        Set<String> keysWhenPreFiltered = EMPLOYEES.stream()
                .filter(e -> e.age() > 40)
                .collect(Collectors.groupingBy(Employee::dept))
                .keySet();
        check("stream.filter first drops keys", new TreeSet<>(keysWhenPreFiltered),
                new TreeSet<>(Set.of("Engineering", "Sales")));

        // flatMapping: the distinct skills available in each department
        Map<String, Set<String>> skillsByDept = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(
                        Employee::dept,
                        TreeMap::new,
                        Collectors.flatMapping(e -> e.skills().stream(),
                                Collectors.toCollection(TreeSet::new))));
        check("skills by dept", skillsByDept, Map.of(
                "Engineering", Set.of("Go", "Java", "Kotlin", "SQL"),
                "Marketing", Set.of("SEO", "Writing"),
                "Sales", Set.of("CRM", "Negotiation")));
    }

    // ========================================================================
    // 13. Sealed interfaces + pattern matching for switch  (Java 21)
    // ------------------------------------------------------------------------
    // Feature: a SEALED interface lists all of its implementations. A switch
    // over it can then be EXHAUSTIVE with no `default` branch. If you add a
    // new Shape later, the compiler flags every switch that forgets it.
    // Type patterns (`case Circle c`) and guards (`when ...`) let the stream's
    // map step dispatch on type cleanly, with no instanceof chains or visitors.
    // ========================================================================
    sealed interface Shape permits Circle, Rectangle, Triangle {}
    record Circle(double radius) implements Shape {}
    record Rectangle(double width, double height) implements Shape {}
    record Triangle(double base, double height) implements Shape {}

    static double area(Shape s) {
        return switch (s) {                       // exhaustive: no default needed
            case Circle c -> Math.PI * c.radius() * c.radius();
            case Rectangle r -> r.width() * r.height();
            case Triangle t -> 0.5 * t.base() * t.height();
        };
    }

    static String describe(Shape s) {
        return switch (s) {
            case Rectangle r when r.width() == r.height() -> "square";   // guarded pattern
            case Rectangle r -> "rectangle";
            case Circle c -> "circle";
            case Triangle t -> "triangle";
        };
    }

    static void example13_sealedInterfacesAndPatternSwitch() {
        header(13, "Sealed types + pattern-matching switch (Java 21)");

        List<Shape> shapes = List.of(
                new Circle(1), new Rectangle(2, 3), new Rectangle(2, 2), new Triangle(4, 5));

        double totalArea = shapes.stream().mapToDouble(AdvancedStreamExamples::area).sum();
        check("total area (2 dp)", Math.round(totalArea * 100) / 100.0, 23.14);   // PI + 6 + 4 + 10

        List<String> kinds = shapes.stream().map(AdvancedStreamExamples::describe).toList();
        check("descriptions", kinds, List.of("circle", "rectangle", "square", "triangle"));

        // Count shapes by kind
        Map<String, Long> countByKind = shapes.stream()
                .collect(Collectors.groupingBy(AdvancedStreamExamples::describe, TreeMap::new, Collectors.counting()));
        check("count by kind", countByKind,
                Map.of("circle", 1L, "rectangle", 1L, "square", 1L, "triangle", 1L));
    }

    // ========================================================================
    // 14. Record patterns  (Java 21)
    // ------------------------------------------------------------------------
    // Feature: record patterns DECONSTRUCT a record into its components right
    // in the pattern, and they can be NESTED. `var` infers component types.
    // They work in switch and in instanceof, which fits nicely inside filter()
    // and map() lambdas.
    // ========================================================================
    record Point(int x, int y) {}
    record Line(Point start, Point end) {}

    static void example14_recordPatterns() {
        header(14, "Record patterns - deconstruction (Java 21)");

        List<Line> lines = List.of(
                new Line(new Point(0, 0), new Point(3, 4)),
                new Line(new Point(1, 1), new Point(4, 5)),
                new Line(new Point(0, 0), new Point(0, 2)));

        // Nested deconstruction in a switch inside map()
        List<Double> lengths = lines.stream()
                .map(line -> switch (line) {
                    case Line(Point(var x1, var y1), Point(var x2, var y2)) ->
                            Math.hypot(x2 - x1, y2 - y1);
                })
                .toList();
        check("line lengths", lengths, List.of(5.0, 5.0, 2.0));

        // Record pattern in instanceof inside filter(): keep points on the diagonal
        List<Object> things = List.of(new Point(1, 1), "not a point", new Point(2, 3), new Point(5, 5), 42);
        List<Point> onDiagonal = things.stream()
                .filter(o -> o instanceof Point(int x, int y) && x == y)
                .map(Point.class::cast)
                .toList();
        check("points with x == y", onDiagonal, List.of(new Point(1, 1), new Point(5, 5)));

        // Vertical lines: deconstruct and test only the x components
        long vertical = lines.stream()
                .filter(l -> l instanceof Line(Point(var x1, var _y1), Point(var x2, var _y2)) && x1 == x2)
                .count();
        check("vertical lines", vertical, 1L);
    }

    // ========================================================================
    // 15. Custom Collector with Collector.of
    // ------------------------------------------------------------------------
    // Feature: when no built-in collector fits, build one from 4 functions:
    //   supplier    - create a new mutable container
    //   accumulator - add one element to a container
    //   combiner    - merge two containers (used by PARALLEL streams)
    //   finisher    - convert the container into the final result
    // Here: a reusable "median" collector.
    // ========================================================================
    static Collector<Integer, ?, Double> toMedian() {
        return Collector.of(
                ArrayList<Integer>::new,                    // supplier
                List::add,                                  // accumulator
                (left, right) -> { left.addAll(right); return left; },   // combiner
                list -> {                                   // finisher
                    if (list.isEmpty()) return Double.NaN;
                    Collections.sort(list);
                    int mid = list.size() / 2;
                    return list.size() % 2 == 1
                            ? list.get(mid)
                            : (list.get(mid - 1) + list.get(mid)) / 2.0;
                });
    }

    static void example15_customCollector() {
        header(15, "Custom Collector via Collector.of");

        check("median odd count", Stream.of(5, 1, 9, 3, 7).collect(toMedian()), 5.0);
        check("median even count", Stream.of(4, 1, 3, 2).collect(toMedian()), 2.5);

        // Works as a downstream collector too: median age per dept
        Map<String, Double> medianAge = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(Employee::dept, TreeMap::new,
                        Collectors.mapping(Employee::age, toMedian())));
        check("median age per dept", medianAge,
                Map.of("Engineering", 34.0, "Marketing", 28.5, "Sales", 34.0));

        // And it is parallel-safe thanks to the combiner
        double parallelMedian = IntStream.rangeClosed(1, 10_001).boxed().parallel().collect(toMedian());
        check("parallel median 1..10001", parallelMedian, 5001.0);
    }

    // ========================================================================
    // 16. Parallel streams
    // ------------------------------------------------------------------------
    // Feature: .parallel() / parallelStream() splits work across the
    // ForkJoin common pool. Rules to get correct results:
    //   - Use stateless, non-interfering lambdas (don't mutate shared state).
    //   - reduce needs a true identity and an ASSOCIATIVE function.
    //   - The 3-arg reduce(identity, accumulator, combiner) is needed when the
    //     result type differs from the element type.
    //   - toList()/collect keep ENCOUNTER ORDER; forEach does NOT (use
    //     forEachOrdered if order matters).
    // Parallel pays off only for large, CPU-bound workloads. Always measure.
    // ========================================================================
    static void example16_parallelStreams() {
        header(16, "Parallel streams - correct usage");

        long sum = LongStream.rangeClosed(1, 1_000_000).parallel().sum();
        check("parallel sum 1..1,000,000", sum, 500_000_500_000L);

        // 3-arg reduce: elements are Strings, result is an int
        int totalLength = Stream.of("alpha", "beta", "gamma", "delta").parallel()
                .reduce(0,
                        (acc, word) -> acc + word.length(),   // accumulator: int + String -> int
                        Integer::sum);                         // combiner: merge partial ints
        check("total length via 3-arg reduce", totalLength, 19);

        // Encounter order preserved by toList even in parallel
        List<Integer> squares = IntStream.rangeClosed(1, 8).parallel().map(i -> i * i).boxed().toList();
        check("parallel toList keeps order", squares, List.of(1, 4, 9, 16, 25, 36, 49, 64));

        // forEachOrdered preserves order; plain forEach would not
        List<Integer> ordered = Collections.synchronizedList(new ArrayList<>());
        IntStream.rangeClosed(1, 8).parallel().forEachOrdered(ordered::add);
        check("forEachOrdered keeps order", ordered, List.of(1, 2, 3, 4, 5, 6, 7, 8));
    }

    // ========================================================================
    // 17. Laziness and short-circuiting
    // ------------------------------------------------------------------------
    // Feature: intermediate operations are LAZY. Elements flow through the
    // whole pipeline ONE AT A TIME ("vertical" processing), and processing
    // stops as soon as a short-circuiting terminal op (findFirst, anyMatch,
    // limit...) has its answer. We record each step in a log to prove it.
    // ========================================================================
    static void example17_lazinessAndShortCircuiting() {
        header(17, "Laziness and short-circuiting");

        List<String> log = new ArrayList<>();

        // 1) No terminal operation -> NOTHING runs at all
        Stream<String> notYetRun = Stream.of("a", "bb", "ccc")
                .filter(s -> { log.add("filter " + s); return true; });
        check("no terminal op -> no work", log, List.of());

        // 2) findFirst stops after the first match. "ccc"/"dddd" are never touched,
        //    and each element goes through filter THEN map before the next one starts.
        String first = Stream.of("a", "bb", "ccc", "dddd")
                .filter(s -> { log.add("filter " + s); return s.length() > 1; })
                .map(s -> { log.add("map " + s); return s.toUpperCase(); })
                .findFirst()
                .orElseThrow();
        check("result", first, "BB");
        check("only needed work was done", log, List.of("filter a", "filter bb", "map bb"));

        // 3) Laziness makes infinite streams usable
        int firstSquareOver1000 = IntStream.iterate(1, i -> i + 1)   // 1, 2, 3, ... forever
                .map(i -> i * i)
                .filter(sq -> sq > 1000)
                .findFirst()
                .getAsInt();
        check("first square > 1000", firstSquareOver1000, 1024);

        // A stream can only be consumed ONCE
        notYetRun.count();
        boolean threw = false;
        try { notYetRun.count(); } catch (IllegalStateException e) { threw = true; }
        check("stream reuse throws", threw, true);
    }

    // ========================================================================
    // 18. Word frequency + top-N  (a classic interview task)
    // ------------------------------------------------------------------------
    // Feature: a full real-world pipeline:
    //   split -> normalize -> groupingBy(identity, counting) -> stream the
    //   map's entrySet -> sort with a COMPOSED comparator -> limit(N).
    // Map.Entry.comparingByValue / comparingByKey are built-in comparators.
    // ========================================================================
    static void example18_wordFrequencyTopN() {
        header(18, "Word frequency + top-N");

        String text = "The quick brown fox jumps over the lazy dog. The fox, the DOG!";

        Map<String, Long> freq = Arrays.stream(text.toLowerCase().split("\\W+"))   // split on non-word chars
                .filter(w -> !w.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        check("'the' count", freq.get("the"), 4L);

        List<String> top3 = freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()   // most frequent first
                        .thenComparing(Map.Entry.comparingByKey()))             // ties: alphabetical
                .limit(3)
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();
        check("top 3 words", top3, List.of("the=4", "dog=2", "fox=2"));
    }

    // ========================================================================
    // 19. Chunking (batching) and sliding windows
    // ------------------------------------------------------------------------
    // Feature: index-based streams. Streams have no built-in "batch" or
    // "window" op in Java 21 (Gatherers arrived later, in Java 24), so we
    // stream over INDEXES with IntStream and use subList views.
    // ========================================================================
    static void example19_chunkingAndSlidingWindows() {
        header(19, "Chunking and sliding windows via IntStream indexes");

        List<Integer> data = IntStream.rangeClosed(1, 10).boxed().toList();

        // Fixed-size batches of 3 (the last batch may be smaller)
        int size = 3;
        List<List<Integer>> chunks = IntStream.range(0, (data.size() + size - 1) / size)   // ceil division
                .mapToObj(i -> data.subList(i * size, Math.min(data.size(), (i + 1) * size)))
                .toList();
        check("chunks of 3", chunks,
                List.of(List.of(1, 2, 3), List.of(4, 5, 6), List.of(7, 8, 9), List.of(10)));

        // Sliding windows of 3 -> moving average
        int window = 3;
        List<Double> movingAvg = IntStream.rangeClosed(0, data.size() - window)
                .mapToObj(i -> data.subList(i, i + window))
                .map(w -> w.stream().mapToInt(Integer::intValue).average().orElseThrow())
                .toList();
        check("moving average (window 3)", movingAvg,
                List.of(2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0));

        // Pairwise differences (window of 2) - e.g. day-over-day change
        List<Integer> prices = List.of(100, 103, 101, 110);
        List<Integer> deltas = IntStream.range(1, prices.size())
                .mapToObj(i -> prices.get(i) - prices.get(i - 1))
                .toList();
        check("day-over-day deltas", deltas, List.of(3, -2, 9));
    }

    // ========================================================================
    // 20. Multi-level grouping + SequencedCollection  (Java 21)
    // ------------------------------------------------------------------------
    // Feature A: groupingBy can be NESTED: the downstream of one groupingBy
    // is another groupingBy, giving Map<K1, Map<K2, V>>.
    //
    // Feature B (Java 21): SequencedCollection / SequencedMap give ordered
    // collections uniform methods: getFirst(), getLast(), reversed(),
    // firstEntry(), lastEntry(). These pair well with sorted stream results.
    // ========================================================================
    static void example20_multiLevelGroupingAndSequencedCollections() {
        header(20, "Multi-level grouping + SequencedCollection (Java 21)");

        Map<String, Map<String, List<String>>> byDeptThenAgeBand = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(
                        Employee::dept,
                        TreeMap::new,
                        Collectors.groupingBy(
                                e -> e.age() >= 30 ? "30+" : "under 30",
                                TreeMap::new,
                                Collectors.mapping(Employee::name, Collectors.toList()))));
        check("dept -> age band -> names", byDeptThenAgeBand, Map.of(
                "Engineering", Map.of("30+", List.of("Alice", "Frank"), "under 30", List.of("Bob")),
                "Marketing", Map.of("30+", List.of("Eve"), "under 30", List.of("Grace")),
                "Sales", Map.of("30+", List.of("Carol"), "under 30", List.of("Dan"))));

        // SequencedCollection methods on a sorted stream result
        List<String> bySalary = EMPLOYEES.stream()
                .sorted(Comparator.comparingDouble(Employee::salary))
                .map(Employee::name)
                .toList();
        check("lowest paid (getFirst)", bySalary.getFirst(), "Dan");
        check("highest paid (getLast)", bySalary.getLast(), "Frank");
        check("top 3 via reversed()", bySalary.reversed().stream().limit(3).toList(),
                List.of("Frank", "Alice", "Bob"));

        // SequencedMap: first/last entries of a sorted TreeMap built by a collector
        TreeMap<String, Long> headCount = EMPLOYEES.stream()
                .collect(Collectors.groupingBy(Employee::dept, TreeMap::new, Collectors.counting()));
        check("firstEntry()", headCount.firstEntry(), Map.entry("Engineering", 3L));
        check("lastEntry()", headCount.lastEntry(), Map.entry("Sales", 2L));
    }
}