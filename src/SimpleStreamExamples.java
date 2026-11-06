import java.util.*;
import java.util.stream.*;

/**
 * ============================================================================
 *  20 SIMPLE JAVA STREAM EXAMPLES  (Java 21)
 * ============================================================================
 *
 *  A Stream is a pipeline of operations applied to a sequence of elements.
 *  Every pipeline has three parts:
 *
 *    1. SOURCE        - where the elements come from (a List, an array, Stream.of, ...)
 *    2. INTERMEDIATE  - zero or more operations that transform the stream
 *                       (filter, map, sorted, distinct, ...). They are LAZY:
 *                       nothing happens until a terminal operation is called.
 *    3. TERMINAL      - exactly one operation that produces a result or side
 *                       effect (collect, toList, forEach, count, reduce, ...).
 *                       After a terminal operation the stream is consumed and
 *                       cannot be reused.
 *
 *  Streams never modify their source. They always produce new results.
 *
 *  HOW TO RUN:
 *      javac SimpleStreamExamples.java
 *      java SimpleStreamExamples
 *
 *  (Or with Java 11+ single-file launch:  java SimpleStreamExamples.java)
 *
 *  Each example calls check(...) which prints the result AND verifies it
 *  against the expected value, so running the file is also a self-test.
 * ============================================================================
 */
public class SimpleStreamExamples {

    // ------------------------------------------------------------------------
    // Tiny test helper: prints the result and fails loudly if it is wrong.
    // ------------------------------------------------------------------------
    private static int passed = 0;

    static <T> void check(String label, T actual, T expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(label + " -> expected " + expected + " but got " + actual);
        }
        passed++;
        System.out.printf("   %-38s %s%n", label, actual);
    }

    static void header(int n, String title) {
        System.out.printf("%n[%02d] %s%n", n, title);
    }

    public static void main(String[] args) {
        example01_creatingStreamsAndForEach();
        example02_filter();
        example03_map();
        example04_collectToList();
        example05_sortedNaturalOrder();
        example06_sortedWithComparator();
        example07_distinct();
        example08_limitAndSkip();
        example09_count();
        example10_mapToIntAndSum();
        example11_average();
        example12_minAndMax();
        example13_matchOperations();
        example14_findFirstAndOptional();
        example15_reduce();
        example16_joining();
        example17_intStreamRange();
        example18_arraysStream();
        example19_stringChars();
        example20_concatAndOfNullable();

        System.out.printf("%nAll %d checks passed.%n", passed);
    }

    // ========================================================================
    // 01. CREATING STREAMS + forEach
    // ------------------------------------------------------------------------
    // Feature: the common ways to obtain a stream, and the forEach terminal op.
    //   - collection.stream()   -> stream over any Collection (List, Set, ...)
    //   - Stream.of(a, b, c)    -> stream from explicit values
    //   - forEach(action)       -> terminal op that runs `action` per element.
    // forEach is meant for side effects (printing, logging). Prefer collectors
    // when you want to build a result.
    // ========================================================================
    static void example01_creatingStreamsAndForEach() {
        header(1, "Creating streams + forEach");

        List<String> fruits = List.of("apple", "banana", "cherry");

        // Source #1: from a collection
        StringBuilder sb = new StringBuilder();
        fruits.stream().forEach(f -> sb.append(f).append(' '));   // side effect per element
        check("list.stream().forEach", sb.toString().trim(), "apple banana cherry");

        // Source #2: from explicit values
        StringBuilder sb2 = new StringBuilder();
        Stream.of("x", "y", "z").forEach(sb2::append);              // method reference instead of lambda
        check("Stream.of(...).forEach", sb2.toString(), "xyz");

        // Printing is the classic forEach use case:
        fruits.stream().forEach(f -> System.out.println("      fruit: " + f));
    }

    // ========================================================================
    // 02. filter
    // ------------------------------------------------------------------------
    // Feature: filter(Predicate) keeps only elements for which the predicate
    // returns true. It is an INTERMEDIATE operation (returns a new Stream).
    // ========================================================================
    static void example02_filter() {
        header(2, "filter - keep elements matching a condition");

        List<Integer> numbers = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        List<Integer> evens = numbers.stream()
                .filter(n -> n % 2 == 0)   // Predicate<Integer>: true = keep
                .toList();
        check("even numbers", evens, List.of(2, 4, 6, 8, 10));

        // Filters can be chained; each one narrows the stream further.
        List<Integer> evenAndGreaterThan5 = numbers.stream()
                .filter(n -> n % 2 == 0)
                .filter(n -> n > 5)
                .toList();
        check("even AND > 5", evenAndGreaterThan5, List.of(6, 8, 10));
    }

    // ========================================================================
    // 03. map
    // ------------------------------------------------------------------------
    // Feature: map(Function) transforms each element into a new value.
    // The output type can differ from the input type (String -> Integer here).
    // ========================================================================
    static void example03_map() {
        header(3, "map - transform each element");

        List<String> names = List.of("alice", "bob", "charlie");

        // Same type in and out: String -> String
        List<String> upper = names.stream()
                .map(String::toUpperCase)          // method reference = s -> s.toUpperCase()
                .toList();
        check("toUpperCase", upper, List.of("ALICE", "BOB", "CHARLIE"));

        // Different type out: String -> Integer
        List<Integer> lengths = names.stream()
                .map(String::length)
                .toList();
        check("lengths", lengths, List.of(5, 3, 7));
    }

    // ========================================================================
    // 04. COLLECTING RESULTS: toList() vs Collectors.toList() / toSet()
    // ------------------------------------------------------------------------
    // Feature: getting results back out of a stream.
    //   - stream.toList()            (Java 16+) -> UNMODIFIABLE list. Shortest form.
    //   - collect(Collectors.toList()) -> a mutable ArrayList (in practice).
    //   - collect(Collectors.toSet())  -> a Set (duplicates removed).
    // ========================================================================
    static void example04_collectToList() {
        header(4, "toList / Collectors.toList / toSet");

        List<String> words = List.of("one", "two", "three", "two");

        List<String> immutable = words.stream().toList();
        check("stream.toList()", immutable, List.of("one", "two", "three", "two"));

        // toList() is unmodifiable: trying to add throws an exception.
        boolean threw = false;
        try {
            immutable.add("four");
        } catch (UnsupportedOperationException e) {
            threw = true;
        }
        check("toList() is unmodifiable", threw, true);

        // Collectors.toList() gives a list you can still modify.
        List<String> mutable = words.stream().collect(Collectors.toList());
        mutable.add("four");
        check("Collectors.toList() mutable", mutable.size(), 5);

        // Collectors.toSet() drops duplicates.
        Set<String> unique = words.stream().collect(Collectors.toSet());
        check("Collectors.toSet()", unique, Set.of("one", "two", "three"));
    }

    // ========================================================================
    // 05. sorted() - natural order
    // ------------------------------------------------------------------------
    // Feature: sorted() with no arguments sorts by the elements' natural order
    // (they must implement Comparable: numbers ascending, Strings alphabetical).
    // sorted() is a STATEFUL intermediate op: it must see all elements first.
    // ========================================================================
    static void example05_sortedNaturalOrder() {
        header(5, "sorted() - natural order");

        List<Integer> nums = List.of(5, 3, 9, 1, 7);
        check("numbers ascending", nums.stream().sorted().toList(), List.of(1, 3, 5, 7, 9));

        List<String> words = List.of("pear", "apple", "fig");
        check("strings alphabetical", words.stream().sorted().toList(), List.of("apple", "fig", "pear"));

        // The original list is untouched: streams never modify their source.
        check("source unchanged", nums, List.of(5, 3, 9, 1, 7));
    }

    // ========================================================================
    // 06. sorted(Comparator)
    // ------------------------------------------------------------------------
    // Feature: sorting with a custom Comparator.
    //   - Comparator.reverseOrder()           -> descending
    //   - Comparator.comparing(keyExtractor)  -> sort by a derived key
    //   - .thenComparing(...)                 -> tie-breaker
    // ========================================================================
    static void example06_sortedWithComparator() {
        header(6, "sorted(Comparator) - custom ordering");

        List<Integer> nums = List.of(5, 3, 9, 1, 7);
        check("descending", nums.stream().sorted(Comparator.reverseOrder()).toList(),
                List.of(9, 7, 5, 3, 1));

        List<String> words = List.of("kiwi", "fig", "banana", "apple", "date");

        // Sort by length; equal lengths keep the tie-breaker (alphabetical).
        List<String> byLengthThenAlpha = words.stream()
                .sorted(Comparator.comparing(String::length)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        check("by length, then alphabetical", byLengthThenAlpha,
                List.of("fig", "date", "kiwi", "apple", "banana"));
    }

    // ========================================================================
    // 07. distinct
    // ------------------------------------------------------------------------
    // Feature: distinct() removes duplicates using equals()/hashCode().
    // It keeps the FIRST occurrence and preserves encounter order.
    // ========================================================================
    static void example07_distinct() {
        header(7, "distinct - remove duplicates");

        List<Integer> withDupes = List.of(3, 1, 3, 2, 1, 4, 2);
        check("distinct", withDupes.stream().distinct().toList(), List.of(3, 1, 2, 4));

        // Combine distinct with map: unique first letters.
        List<Character> firstLetters = Stream.of("apple", "avocado", "banana", "blueberry", "cherry")
                .map(s -> s.charAt(0))
                .distinct()
                .toList();
        check("unique first letters", firstLetters, List.of('a', 'b', 'c'));
    }

    // ========================================================================
    // 08. limit and skip
    // ------------------------------------------------------------------------
    // Feature: slicing a stream.
    //   - limit(n) -> keep at most the first n elements (short-circuiting).
    //   - skip(n)  -> drop the first n elements.
    // Together they give "pagination": skip(page * size).limit(size).
    // ========================================================================
    static void example08_limitAndSkip() {
        header(8, "limit and skip - slicing / pagination");

        List<Integer> nums = List.of(10, 20, 30, 40, 50, 60, 70);

        check("limit(3)", nums.stream().limit(3).toList(), List.of(10, 20, 30));
        check("skip(4)", nums.stream().skip(4).toList(), List.of(50, 60, 70));

        int pageSize = 3, page = 1;                       // 0-based page index
        List<Integer> page1 = nums.stream()
                .skip((long) page * pageSize)
                .limit(pageSize)
                .toList();
        check("page 1 (size 3)", page1, List.of(40, 50, 60));
    }

    // ========================================================================
    // 09. count
    // ------------------------------------------------------------------------
    // Feature: count() is a terminal op that returns the number of elements
    // as a long. Often used after filter to answer "how many match?".
    // ========================================================================
    static void example09_count() {
        header(9, "count - how many elements");

        List<String> names = List.of("Anna", "Bob", "Alex", "Brian", "Amy");

        long total = names.stream().count();
        check("total", total, 5L);

        long startsWithA = names.stream().filter(n -> n.startsWith("A")).count();
        check("starting with 'A'", startsWithA, 3L);
    }

    // ========================================================================
    // 10. mapToInt + sum (primitive streams)
    // ------------------------------------------------------------------------
    // Feature: primitive streams (IntStream, LongStream, DoubleStream).
    // mapToInt converts Stream<T> to IntStream, which avoids boxing Integer
    // objects and adds numeric terminal ops: sum(), average(), min(), max().
    // ========================================================================
    static void example10_mapToIntAndSum() {
        header(10, "mapToInt + sum - primitive streams");

        List<Integer> prices = List.of(19, 5, 42, 8);
        int total = prices.stream()
                .mapToInt(Integer::intValue)   // Stream<Integer> -> IntStream (unboxing)
                .sum();                        // only available on primitive streams
        check("sum of prices", total, 74);

        // Sum of lengths of strings.
        int totalChars = Stream.of("hi", "hello", "hey")
                .mapToInt(String::length)
                .sum();
        check("total characters", totalChars, 10);
    }

    // ========================================================================
    // 11. average -> OptionalDouble
    // ------------------------------------------------------------------------
    // Feature: average() returns OptionalDouble because an EMPTY stream has
    // no average. Use orElse(...) to supply a default.
    // ========================================================================
    static void example11_average() {
        header(11, "average - OptionalDouble");

        OptionalDouble avg = IntStream.of(1, 2, 3, 4, 5).average();
        check("average of 1..5", avg.getAsDouble(), 3.0);

        // Empty stream: no value, so we fall back to a default.
        double emptyAvg = IntStream.empty().average().orElse(0.0);
        check("average of empty (default)", emptyAvg, 0.0);
    }

    // ========================================================================
    // 12. min and max
    // ------------------------------------------------------------------------
    // Feature: min/max with a Comparator on object streams return Optional<T>.
    // On primitive streams, min()/max() need no Comparator.
    // ========================================================================
    static void example12_minAndMax() {
        header(12, "min and max");

        List<String> words = List.of("elephant", "cat", "giraffe", "ox");

        Optional<String> shortest = words.stream().min(Comparator.comparing(String::length));
        Optional<String> longest = words.stream().max(Comparator.comparing(String::length));
        check("shortest word", shortest.orElseThrow(), "ox");
        check("longest word", longest.orElseThrow(), "elephant");

        int maxNum = IntStream.of(4, 17, 9).max().getAsInt();   // primitive version
        check("max of 4,17,9", maxNum, 17);
    }

    // ========================================================================
    // 13. anyMatch / allMatch / noneMatch
    // ------------------------------------------------------------------------
    // Feature: boolean "match" terminal ops. They SHORT-CIRCUIT: they stop as
    // soon as the answer is known (e.g. anyMatch stops at the first true).
    // Edge case: on an empty stream allMatch and noneMatch return true.
    // ========================================================================
    static void example13_matchOperations() {
        header(13, "anyMatch / allMatch / noneMatch");

        List<Integer> ages = List.of(22, 35, 17, 41);

        check("anyMatch(age < 18)", ages.stream().anyMatch(a -> a < 18), true);
        check("allMatch(age > 16)", ages.stream().allMatch(a -> a > 16), true);
        check("noneMatch(age > 100)", ages.stream().noneMatch(a -> a > 100), true);

        // Vacuous truth on empty streams:
        check("empty.allMatch(anything)", Stream.<Integer>empty().allMatch(a -> a > 1000), true);
    }

    // ========================================================================
    // 14. findFirst + Optional
    // ------------------------------------------------------------------------
    // Feature: findFirst() returns Optional<T>, the first element (if any).
    // Optional forces you to handle the "not found" case explicitly:
    //   orElse(default), orElseGet(supplier), orElseThrow(), ifPresent(...),
    //   map(...) to transform the value if present.
    // ========================================================================
    static void example14_findFirstAndOptional() {
        header(14, "findFirst + Optional");

        List<String> emails = List.of("bob@site.com", "admin@corp.com", "amy@corp.com");

        Optional<String> firstCorp = emails.stream()
                .filter(e -> e.endsWith("@corp.com"))
                .findFirst();
        check("first @corp.com", firstCorp.orElse("none"), "admin@corp.com");

        // Not found -> default value
        String gov = emails.stream()
                .filter(e -> e.endsWith(".gov"))
                .findFirst()
                .orElse("none");
        check("first .gov (missing)", gov, "none");

        // Optional.map transforms the value only if present.
        String user = firstCorp.map(e -> e.substring(0, e.indexOf('@'))).orElse("?");
        check("username via Optional.map", user, "admin");
    }

    // ========================================================================
    // 15. reduce
    // ------------------------------------------------------------------------
    // Feature: reduce combines all elements into one value.
    //   - reduce(identity, accumulator) -> returns T (identity if empty)
    //   - reduce(accumulator)           -> returns Optional<T> (empty if empty)
    // The accumulator must be associative: (a+b)+c == a+(b+c).
    // ========================================================================
    static void example15_reduce() {
        header(15, "reduce - fold elements into one value");

        List<Integer> nums = List.of(1, 2, 3, 4, 5);

        int sum = nums.stream().reduce(0, Integer::sum);          // 0 is the identity for +
        check("sum via reduce", sum, 15);

        int product = nums.stream().reduce(1, (a, b) -> a * b);   // 1 is the identity for *
        check("product via reduce", product, 120);

        // No identity -> Optional (because the stream might be empty)
        Optional<String> longest = Stream.of("hi", "hello", "hey")
                .reduce((a, b) -> a.length() >= b.length() ? a : b);
        check("longest via reduce", longest.orElseThrow(), "hello");
    }

    // ========================================================================
    // 16. Collectors.joining
    // ------------------------------------------------------------------------
    // Feature: joining concatenates strings, optionally with a delimiter,
    // a prefix and a suffix. Much cleaner than a manual StringBuilder loop.
    // ========================================================================
    static void example16_joining() {
        header(16, "Collectors.joining - build strings");

        List<String> langs = List.of("Java", "Kotlin", "Scala");

        check("joining()", langs.stream().collect(Collectors.joining()), "JavaKotlinScala");
        check("joining(\", \")", langs.stream().collect(Collectors.joining(", ")), "Java, Kotlin, Scala");
        check("joining(delim, prefix, suffix)",
                langs.stream().collect(Collectors.joining(" | ", "[", "]")),
                "[Java | Kotlin | Scala]");
    }

    // ========================================================================
    // 17. IntStream.range / rangeClosed
    // ------------------------------------------------------------------------
    // Feature: generating number sequences without a collection.
    //   - range(a, b)       -> a .. b-1   (end EXCLUSIVE)
    //   - rangeClosed(a, b) -> a .. b     (end INCLUSIVE)
    // boxed() converts IntStream -> Stream<Integer> so you can call toList().
    // A stream-based replacement for the classic for (int i = 0; ...) loop.
    // ========================================================================
    static void example17_intStreamRange() {
        header(17, "IntStream.range / rangeClosed");

        check("range(1, 5)", IntStream.range(1, 5).boxed().toList(), List.of(1, 2, 3, 4));
        check("rangeClosed(1, 5)", IntStream.rangeClosed(1, 5).boxed().toList(), List.of(1, 2, 3, 4, 5));

        // Squares of 1..5
        List<Integer> squares = IntStream.rangeClosed(1, 5).map(i -> i * i).boxed().toList();
        check("squares 1..5", squares, List.of(1, 4, 9, 16, 25));

        // Sum of 1..100 (Gauss)
        check("sum 1..100", IntStream.rangeClosed(1, 100).sum(), 5050);
    }

    // ========================================================================
    // 18. Arrays.stream
    // ------------------------------------------------------------------------
    // Feature: streaming over arrays. Arrays.stream(int[]) gives an IntStream
    // directly; Arrays.stream(T[]) gives a Stream<T>. You can also stream a
    // sub-range: Arrays.stream(array, fromInclusive, toExclusive).
    // toArray() turns a stream back into an array.
    // ========================================================================
    static void example18_arraysStream() {
        header(18, "Arrays.stream - streams from arrays");

        int[] scores = {88, 92, 75, 64, 99};
        check("max score", Arrays.stream(scores).max().getAsInt(), 99);
        check("sum of indexes 1..3", Arrays.stream(scores, 1, 4).sum(), 92 + 75 + 64);

        String[] cities = {"Paris", "Oslo", "Rome", "Lima"};
        String[] fourLetter = Arrays.stream(cities)
                .filter(c -> c.length() == 4)
                .toArray(String[]::new);          // back to a typed array
        check("4-letter cities", Arrays.toString(fourLetter), "[Oslo, Rome, Lima]");
    }

    // ========================================================================
    // 19. String.chars()
    // ------------------------------------------------------------------------
    // Feature: String.chars() returns an IntStream of character codes.
    // Cast back to char with mapToObj(c -> (char) c) when you need Characters.
    // Great for counting / filtering characters in text.
    // ========================================================================
    static void example19_stringChars() {
        header(19, "String.chars() - stream over characters");

        String text = "Hello Streams";

        long vowels = text.toLowerCase().chars()
                .filter(c -> "aeiou".indexOf(c) >= 0)
                .count();
        check("vowel count", vowels, 4L);          // e, o, e, a

        long upperCase = text.chars().filter(Character::isUpperCase).count();
        check("uppercase count", upperCase, 2L);

        // Reverse a string using chars -> Character -> joining
        String reversed = new StringBuilder(text).reverse().chars()
                .mapToObj(c -> String.valueOf((char) c))
                .collect(Collectors.joining());
        check("reversed", reversed, "smaertS olleH");
    }

    // ========================================================================
    // 20. Stream.concat and Stream.ofNullable
    // ------------------------------------------------------------------------
    // Feature: combining streams and safely handling nulls.
    //   - Stream.concat(a, b)      -> all elements of a followed by all of b.
    //   - Stream.ofNullable(x)     -> (Java 9+) empty stream if x is null,
    //                                 otherwise a one-element stream.
    //                                 Avoids NullPointerExceptions.
    // ========================================================================
    static void example20_concatAndOfNullable() {
        header(20, "Stream.concat + Stream.ofNullable");

        List<String> morning = List.of("coffee", "toast");
        List<String> evening = List.of("pasta", "tea");

        List<String> allDay = Stream.concat(morning.stream(), evening.stream()).toList();
        check("concat", allDay, List.of("coffee", "toast", "pasta", "tea"));

        String present = "snack";
        String missing = null;

        // ofNullable turns a possibly-null value into a 0- or 1-element stream,
        // so it can be concatenated without null checks.
        List<String> extras = Stream.concat(Stream.ofNullable(present), Stream.ofNullable(missing))
                .toList();
        check("ofNullable skips null", extras, List.of("snack"));
    }
}