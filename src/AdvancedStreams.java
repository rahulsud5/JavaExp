/*
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

*/
/**
 * Master Streams Demonstration Class.
 * Combines 20 Foundational Stream examples and 25 Advanced Enterprise patterns
 * into a single unified, clean, and completely runnable compilation space.
 *//*

public class MasterStreamsDemo {

    // --- Domain Models & Helpers ---
    record SimpleUser(String name, int age) {}
    record SimpleItem(String name, String category) {}

    record Order(String department, String status, double amount) {}
    record Team(String name, List<String> tags) {}
    record Department(String name, List<Team> teams) {}
    record Metric(int timestamp, double cpuUsage) {}
    record User(int id, String name, String role, int age) {}
    record Employee(String name, String department, double salary) {}
    record Product(String name, String category, double price, double rating) {}
    record Transaction(String id, String currency, double amount) {}
    record Node(String name, List<Node> children) {}

    */
/**
     * Custom state tracking object used in Advanced Example #4.
     *//*

    static class MathSummary {
        long sum = 0;
        long product = 1;
        void accept(long value) { sum += value; product *= value; }
        MathSummary combine(MathSummary other) { this.sum += other.sum; this.product *= other.product; return this; }
    }

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("=== PART 1: 20 SIMPLER FOUNDATIONAL EXAMPLES  ===");
        System.out.println("=================================================");

        runExample("S1. Basic Filter", MasterStreamsDemo::runBasicFilter);
        runExample("S2. Basic Map Transformation", MasterStreamsDemo::runBasicMap);
        runExample("S3. Stream to List Collection", MasterStreamsDemo::runStreamToList);
        runExample("S4. Basic Sorting (Natural Order)", MasterStreamsDemo::runBasicSorting);
        runExample("S5. Reverse Sorting Strings", MasterStreamsDemo::runReverseSorting);
        runExample("S6. Distinct Elements Extraction", MasterStreamsDemo::runDistinctElements);
        runExample("S7. Limiting Stream Size", MasterStreamsDemo::runLimitStream);
        runExample("S8. Skipping Stream Elements", MasterStreamsDemo::runSkipElements);
        runExample("S9. Simple Reduce (Summing)", MasterStreamsDemo::runSimpleReduceSum);
        runExample("S10. Primitive IntStream Range", MasterStreamsDemo::runIntStreamRange);
        runExample("S11. Matching Operations (anyMatch)", MasterStreamsDemo::runAnyMatchExample);
        runExample("S12. All Match Check (allMatch)", MasterStreamsDemo::runAllMatchExample);
        runExample("S13. None Match Check (noneMatch)", MasterStreamsDemo::runNoneMatchExample);
        runExample("S14. Finding First Element", MasterStreamsDemo::runFindFirst);
        runExample("S15. Finding Any Element", MasterStreamsDemo::runFindAny);
        runExample("S16. Standard Grouping By Category", MasterStreamsDemo::runSimpleGroupingBy);
        runExample("S17. Joining Strings with Delimiter", MasterStreamsDemo::runSimpleJoining);
        runExample("S18. Maximum Value Calculation", MasterStreamsDemo::runMaxCalculation);
        runExample("S19. Minimum Value Calculation", MasterStreamsDemo::runMinCalculation);
        runExample("S20. Basic FlatMap (Flattening Arrays)", MasterStreamsDemo::runSimpleFlatMap);

        System.out.println("\n=============================================");
        System.out.println("=== PART 2: 25 ADVANCED ENTERPRISE EXAMPLES  ===");
        System.out.println("=============================================");

        runExample("1. Multi-Level Grouping", MasterStreamsDemo::runMultiLevelGrouping);
        runExample("2. Deep Hierarchy Flattening", MasterStreamsDemo::runDeepFlattening);
        runExample("3. Conditional Slicing (takeWhile)", MasterStreamsDemo::runStreamSlicing);
        runExample("4. Custom Inline Collector", MasterStreamsDemo::runCustomCollector);
        runExample("5. Conditional Infinite Stream", MasterStreamsDemo::runInfiniteStreamFibonacci);
        runExample("6. Custom Distinct by Property Key", MasterStreamsDemo::runDistinctByProperty);
        runExample("7. Partitioning with Downstream Reduction", MasterStreamsDemo::runPartitioningDownstream);
        runExample("8. Zipper Merge of Two Streams (Indices Match)", MasterStreamsDemo::runZipperMerge);
        runExample("9. Sliding Window Aggregation Over a List", MasterStreamsDemo::runSlidingWindow);
        runExample("10. FlatMapping Optionals to Prune Nulls/Empties", MasterStreamsDemo::runFlatMapOptional);
        runExample("11. Frequency Mapping & Top-K Element Extraction", MasterStreamsDemo::runTopKElements);
        runExample("12. Grouping by Range (Custom Bucket Mapping)", MasterStreamsDemo::runGroupingByRange);
        runExample("13. Merging Disparate Maps via Stream.concat", MasterStreamsDemo::runMapMerging);
        runExample("14. Dynamic Sorting with Composed Comparators", MasterStreamsDemo::runDynamicSorting);
        runExample("15. Batching / Chunking a Stream into Fixed Sizes", MasterStreamsDemo::runStreamBatching);
        runExample("16. Processing Recursive Tree Hierarchies", MasterStreamsDemo::runRecursiveTree);
        runExample("17. Parallel Stream Thread Isolation via ForkJoinPool", MasterStreamsDemo::runParallelIsolation);
        runExample("18. Inverting a Multi-Value Map (Exploding Keys)", MasterStreamsDemo::runInvertMap);
        runExample("19. Teeing / Compounding Two Independent Collectors", MasterStreamsDemo::runTeeingCollector);
        runExample("20. String Tokenization & Text Regex Streaming", MasterStreamsDemo::runRegexStreaming);
        runExample("21. Cartesian Product Generation via Cross-FlatMap", MasterStreamsDemo::runCartesianProduct);
        runExample("22. Intersecting Multiple Collections Efficiently", MasterStreamsDemo::runStreamIntersection);
        runExample("23. Collecting Stream directly into an Immutable EnumMap", MasterStreamsDemo::runEnumMapCollection);
        runExample("24. Custom Parallel Combiner for String Concatenation", MasterStreamsDemo::runCustomStringCombiner);
        runExample("25. Handling Checked Exceptions gracefully inside Lambdas", MasterStreamsDemo::runCheckedExceptionsInStreams);
    }

    private static void runExample(String title, Runnable method) {
        System.out.println("\n--- " + title + " ---");
        try { method.run(); } catch (Exception e) { e.printStackTrace(); }
    }

    // =========================================================================
    // PART 1 IMPLEMENTATIONS
    // =========================================================================

    private static void runBasicFilter() {
        List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6);
        List<Integer> evens = numbers.stream().filter(n -> n % 2 == 0).toList();
        System.out.println("Even Numbers: " + evens);
    }

    private static void runBasicMap() {
        List<String> names = List.of("alice", "bob", "charlie");
        List<String> upper = names.stream().map(String::toUpperCase).toList();
        System.out.println("Uppercase Names: " + upper);
    }

    private static void runStreamToList() {
        Stream<String> stream = Stream.of("X", "Y", "Z");
        List<String> collected = stream.collect(Collectors.toList());
        System.out.println("Collected List: " + collected);
    }

    private static void runBasicSorting() {
        List<Integer> numbers = List.of(5, 3, 1, 4, 2);
        List<Integer> sorted = numbers.stream().sorted().toList();
        System.out.println("Sorted Ascending: " + sorted);
    }

    private static void runReverseSorting() {
        List<String> fruits = List.of("Apple", "Orange", "Banana");
        List<String> reversed = fruits.stream().sorted(Comparator.reverseOrder()).toList();
        System.out.println("Sorted Descending: " + reversed);
    }

    private static void runDistinctElements() {
        List<Integer> duplicates = List.of(1, 2, 2, 3, 3, 3, 4);
        List<Integer> unique = duplicates.stream().distinct().toList();
        System.out.println("Unique Elements: " + unique);
    }

    private static void runLimitStream() {
        List<Integer> infiniteSimulated = List.of(1, 2, 3, 4, 5, 6, 7);
        List<Integer> truncated = infiniteSimulated.stream().limit(3).toList();
        System.out.println("First 3 elements: " + truncated);
    }

    private static void runSkipElements() {
        List<Integer> numbers = List.of(1, 2, 3, 4, 5);
        List<Integer> skipped = numbers.stream().skip(2).toList();
        System.out.println("Skipped first 2: " + skipped);
    }

    private static void runSimpleReduceSum() {
        List<Integer> numbers = List.of(1, 2, 3, 4);
        int sum = numbers.stream().reduce(0, Integer::sum);
        System.out.println("Reduced Sum Total: " + sum);
    }

    private static void runIntStreamRange() {
        int sumOfOneToFour = IntStream.range(1, 5).sum();
        System.out.println("Primitive Range Sum (1 to 4): " + sumOfOneToFour);
    }

    private static void runAnyMatchExample() {
        List<String> keywords = List.of("Java", "Python", "C++");
        boolean hasJava = keywords.stream().anyMatch(s -> s.equals("Java"));
        System.out.println("Contains 'Java'? " + hasJava);
    }

    private static void runAllMatchExample() {
        List<Integer> ages = List.of(20, 25, 30);
        boolean allAdults = ages.stream().allMatch(age -> age >= 18);
        System.out.println("Are all elements adults? " + allAdults);
    }

    private static void runNoneMatchExample() {
        List<String> items = List.of("Apple", "Banana", "Cherry");
        boolean noMatchFound = items.stream().noneMatch(s -> s.contains("Z"));
        System.out.println("No words contain 'Z'? " + noMatchFound);
    }
    private static void runFindFirst() {
        List names = List.of("Bob", "Alice", "Charlie");
        Optional first = names.stream().findFirst();
        first.ifPresent(name -> System.out.println("First element: " + name));
    }
    private static void runFindAny() {
        List items = List.of("One", "Two", "Three");
        Optional match = items.stream().findAny();
        match.ifPresent(m -> System.out.println("Found any match: " + m));
    }
    private static void runSimpleGroupingBy() {
        List shoppingList = List.of(
                new SimpleItem("Apple", "Fruit"), new SimpleItem("Broccoli", "Veggie"), new SimpleItem("Banana", "Fruit")
        );
        Map<String, List> grouped = shoppingList.stream().collect(Collectors.groupingBy(SimpleItem::category));
        System.out.println("Grouped by Category: " + grouped);
    }
    private static void runSimpleJoining() {
        List words = List.of("Java", "Streams", "Rocks");
        String result = words.stream().collect(Collectors.joining(" -> "));
        System.out.println("Joined String: " + result);
    }
    private static void runMaxCalculation() {
        List values = List.of(42, 12, 89, 5);
        Optional maximum = values.stream().max(Integer::compareTo);
        maximum.ifPresent(max -> System.out.println("Max Value: " + max));
    }
    private static void runMinCalculation() {
        List users = List.of(new SimpleUser("Alice", 23), new SimpleUser("Bob", 17));
        Optional youngest = users.stream().min(Comparator.comparingInt(SimpleUser::age));
        youngest.ifPresent(u -> System.out.println("Youngest User: " + u.name()));
    }
    private static void runSimpleFlatMap() {
        String[][] nestedData = {{"a", "b"}, {"c", "d"}};
        List flatList = Arrays.stream(nestedData).flatMap(Arrays::stream).toList();
        System.out.println("Flattened Array List: " + flatList);
    }
    // =========================================================================
// PART 2 IMPLEMENTATIONS
// =========================================================================
    private static void runMultiLevelGrouping() {
        List orders = List.of(
                new Order("Electronics", "SHIPPED", 1200.0), new Order("Electronics", "PENDING", 450.0),
                new Order("Clothing", "SHIPPED", 150.0), new Order("Clothing", "PENDING", 80.0)
        );
        Map<String, Map<String, Double>> summary = orders.stream()
                .collect(Collectors.groupingBy(Order::department, Collectors.groupingBy(Order::status, Collectors.summingDouble(Order::amount))));
        System.out.println(summary);
    }
    private static void runDeepFlattening() {
        List departments = List.of(
                new Department("Eng", List.of(new Team("BE", List.of("Java", "Docker")), new Team("FE", List.of("React", "Java"))))
        );
        List tags = departments.stream()
                .flatMap(d -> d.teams().stream()).flatMap(t -> t.tags().stream()).distinct().sorted().toList();
        System.out.println(tags);
    }
    private static void runStreamSlicing() {
        Stream metrics = Stream.of(new Metric(1, 45.0), new Metric(2, 60.0), new Metric(3, 95.0), new Metric(4, 20.0));
        metrics.takeWhile(m -> m.cpuUsage() < 90.0).forEach(m -> System.out.println("Time " + m.timestamp() + ": " + m.cpuUsage() + "%"));
    }
    private static void runCustomCollector() {
        Stream numbers = Stream.of(2L, 3L, 4L);
        MathSummary stats = numbers.collect(Collector.of(MathSummary::new, MathSummary::accept, MathSummary::combine));
        System.out.println("Sum: " + stats.sum + ", Product: " + stats.product);
    }
    private static void runInfiniteStreamFibonacci() {
        Stream.iterate(new int[]{0, 1}, pair -> pair[0] <= 100, pair -> new int[]{pair[1], pair[0] + pair[1]})
                .map(pair -> pair[0])
                .forEach(num -> System.out.print(num + " "));
        System.out.println();
    }
    private static void runDistinctByProperty() {
        List users = List.of(new User(1, "Alice", "Admin", 25), new User(2, "Bob", "User", 30), new User(3, "Alice", "Guest", 22));
        List distinctByName = users.stream().filter(distinctByKey(User::name)).toList();
        System.out.println(distinctByName);
    }
    private static  Predicate distinctByKey(Function keyExtractor) {
        Set seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }
    private static void runPartitioningDownstream() {
        List employees = List.of(new Employee("A", "HR", 4000), new Employee("B", "IT", 7000), new Employee("C", "IT", 5500));
        Map<Boolean, Long> highlyPaidCountByDept = employees.stream()
                .collect(Collectors.partitioningBy(e -> e.salary() > 5000, Collectors.counting()));
        System.out.println("High salary status map (True=Count, False=Count): " + highlyPaidCountByDept);
    }
    private static void runZipperMerge() {
        List names = List.of("Alice", "Bob", "Charlie");
        List scores = List.of(85, 92, 78);
        List zipped = IntStream.range(0, Math.min(names.size(), scores.size()))
                .mapToObj(i -> names.get(i) + ": " + scores.get(i))
                .toList();
        System.out.println(zipped);
    }
    private static void runSlidingWindow() {
        List nums = List.of(1, 2, 3, 4, 5);
        int windowSize = 3;
        List<List> windows = IntStream.range(0, nums.size() - windowSize + 1)
                .mapToObj(i -> nums.subList(i, i + windowSize))
                .toList();
        System.out.println("Sliding Windows: " + windows);
    }
    private static void runFlatMapOptional() {
        List<Optional> optionalList = List.of(Optional.of("Valid"), Optional.empty(), Optional.of("Data"));
        List presentValues = optionalList.stream().flatMap(Optional::stream).toList();
        System.out.println(presentValues);
    }
    private static void runTopKElements() {
        List items = List.of("apple", "banana", "apple", "cherry", "banana", "apple");
        int k = 2;
        List topK = items.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(k)
                .map(Map.Entry::getKey)
                .toList();
        System.out.println("Top " + k + " items: " + topK);
    }
    private static void runGroupingByRange() {
        List users = List.of(new User(1, "A", "U", 15), new User(2, "B", "U", 25), new User(3, "C", "U", 45));
        Map<String, List> groupedByAgeBracket = users.stream()
                .collect(Collectors.groupingBy(u -> {
                    if (u.age() < 20) return "Youth";
                    if (u.age() < 40) return "Adult";
                    return "Senior";
                }));
        System.out.println(groupedByAgeBracket);
    }
    private static void runMapMerging() {
        Map<String, Integer> map1 = Map.of("apple", 2, "banana", 3);
        Map<String, Integer> map2 = Map.of("banana", 4, "cherry", 5);
        Map<String, Integer> merged = Stream.concat(map1.entrySet().stream(), map2.entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, Integer::sum));
        System.out.println(merged);
    }
    private static void runDynamicSorting() {
        List products = List.of(new Product("A", "X", 10.0, 4.5), new Product("B", "X", 10.0, 4.8), new Product("C", "Y", 5.0, 4.0));
        Comparator comp = Comparator.comparing(Product::category)
                .thenComparing(Comparator.comparing(Product::price).reversed())
                .thenComparing(Product::rating);
        List sorted = products.stream().sorted(comp).toList();
        System.out.println(sorted);
    }
    private static void runStreamBatching() {
        List numbers = IntStream.rangeClosed(1, 10).boxed().toList();
        int batchSize = 3;
        Collection<List> batched = IntStream.range(0, numbers.size())
                .boxed()
                .collect(Collectors.groupingBy(index -> index / batchSize))
                .values().stream()
                .map(indices -> indices.stream().map(numbers::get).toList())
                .toList();
        System.out.println(batched);
    }
    private static void runRecursiveTree() {
        Node root = new Node("Root", List.of(new Node("Child1", List.of(new Node("GrandChild1", List.of()))), new Node("Child2", List.of())));
        List flatNodeNames = flattenTree(root).map(Node::name).toList();
        System.out.println("Flattened Tree Nodes: " + flatNodeNames);
    }
    private static Stream flattenTree(Node node) {
        return Stream.concat(Stream.of(node), node.children().stream().flatMap(MasterStreamsDemo::flattenTree));
    }
    private static void runParallelIsolation() {
        int[] data = IntStream.rangeClosed(1, 10_000).toArray();
        java.util.concurrent.ForkJoinPool customPool = new java.util.concurrent.ForkJoinPool(4);
        try {
            long total = customPool.submit(() -> Arrays.stream(data).parallel().map(x -> x * 2).summaryStatistics().getSum()).get();
            System.out.println("Custom ForkJoinPool execution complete. Sum: " + total);
        } catch (Exception e) { e.printStackTrace(); }
        finally { customPool.shutdown(); }
    }
    private static void runInvertMap() {
        Map<String, List> userRoles = Map.of("Alice", List.of("ADMIN", "USER"), "Bob", List.of("USER"));
        Map<String, List> roleToUsers = userRoles.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(role -> Map.entry(role, entry.getKey())))
                .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        System.out.println(roleToUsers);
    }
    private static void runTeeingCollector() {
        List ratings = List.of(4, 5, 2, 5, 3, 4);
        String stats = ratings.stream().collect(Collectors.teeing(
                Collectors.filtering(r -> r >= 4, Collectors.counting()),
                Collectors.averagingDouble(Integer::doubleValue),
                (highCount, avg) -> "High Ratings Count: " + highCount + ", Overall Average: " + avg
        ));
        System.out.println(stats);
    }
    private static void runRegexStreaming() {
        String logLine = "ERROR: System failure; WARN: Low memory; ERROR: DB timeout;";
        List errors = Pattern.compile(";\s*").splitAsStream(logLine)
                .filter(line -> line.startsWith("ERROR"))
                .toList();
        System.out.println(errors);
    }
    private static void runCartesianProduct() {
        List suits = List.of("Hearts", "Spades");
        List ranks = List.of("Ace", "King");
        List deck = suits.stream()
                .flatMap(suit -> ranks.stream().map(rank -> rank + " of " + suit))
                .toList();
        System.out.println(deck);
    }
    private static void runStreamIntersection() {
        List list1 = List.of("a", "b", "c");
        List list2 = List.of("b", "c", "d");
        Set set2 = new HashSet<>(list2);
        List intersection = list1.stream().filter(set2::contains).toList();
        System.out.println("Intersection: " + intersection);
    }
    enum Type { RETAIL, WHOLESALE }
    record Merchant(String name, Type type) {}
    private static void runEnumMapCollection() {
        List merchants = List.of(new Merchant("M1", Type.RETAIL), new Merchant("M2", Type.WHOLESALE));
        EnumMap<Type, List> collected = merchants.stream()
                .collect(Collectors.groupingBy(Merchant::type, () -> new EnumMap<>(Type.class), Collectors.toList()));
        System.out.println(collected);
    }
    private static void runCustomStringCombiner() {
        List phrases = List.of("Concurrent", "Pipelines", "Accelerate", "Execution");
        String result = phrases.parallelStream()
                .reduce("",
                        (partial, element) -> partial.isEmpty() ? element : partial + "|" + element,
                        (left, right) -> left.isEmpty() ? right : (right.isEmpty() ? left : left + "|" + right)
                );
        System.out.println("Reduced Parallel String: " + result);
    }
    private static void runCheckedExceptionsInStreams() {
        List classNames = List.of("java.lang.String", "java.lang.InvalidClassNameHere", "java.util.ArrayList");
        List<Class<?>> loadedClasses = classNames.stream()
                .map(uncheck(Class::forName))
                .filter(Objects::nonNull)
                .toList();
        System.out.println("Successfully loaded classes: " + loadedClasses);
    }
    @FunctionalInterface
    interface ThrowingFunction<T, R, E extends Exception> {
        R apply(T t) throws E;
    }
    private static <T, R> Function<T, R> uncheck(ThrowingFunction<T, R, Exception> f) {
        return t -> {
            try { return f.apply(t); }
            catch (Exception e) { System.out.println("Intercepted Exception: " + e.getMessage()); return null; }
        };
    }
}*/
