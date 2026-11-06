import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

/**
 * =============================================================================
 *  CORE JAVA SHOWCASE: a library / inventory domain
 * =============================================================================
 *
 *  A tour of the advanced core-Java concepts every senior developer should
 *  know. Each section is a small, realistic scenario with detailed comments.
 *
 *   1. OOP           abstract classes, interfaces (default/static/private
 *                    methods), polymorphism, overloading vs overriding,
 *                    covariant returns, diamond-conflict resolution
 *   2. Object init   static/instance initializer order, the danger of
 *                    calling overridable methods from a constructor
 *   3. Nested types  static nested, inner, local and anonymous classes;
 *                    a custom Iterable
 *   4. Enums         fields, constructors, constant-specific bodies,
 *                    EnumSet and EnumMap
 *   5. Generics      generic classes/methods, bounded types, wildcards
 *                    (PECS), multiple bounds, recursive generics, type
 *                    erasure, type tokens, heap pollution, type-safe
 *                    heterogeneous containers
 *   6. Collections   List/Set/Map/Queue/Deque implementations and when to
 *                    use each, NavigableMap, LRU cache, PriorityQueue,
 *                    equals/hashCode contract, fail-fast iterators, views
 *                    vs copies, IdentityHashMap
 *   7. Exceptions    checked vs unchecked, custom exceptions, chaining,
 *                    try-with-resources, suppressed exceptions, finally
 *   8. Functional    functional interfaces, 4 kinds of method references,
 *                    composition, currying, closures, laziness
 *   9. Language      autoboxing pitfalls, String pool, varargs, pass-by-
 *      gotchas       value, integer overflow, immutability
 *  10. Reflection    custom runtime annotations + a reflective validator,
 *                    reading generic type information that survives erasure
 *
 *  RUN (JDK 17+; tested on JDK 21):   java CoreJavaShowcase.java
 *  The program checks its own results and ends with "All N checks passed."
 * =============================================================================
 */
public class CoreJavaShowcase {

    public static void main(String[] args) throws Exception {
        section1_oop();
        section2_initializationOrder();
        section3_nestedClasses();
        section4_enums();
        section5_generics();
        section6_collections();
        section7_exceptions();
        section8_functional();
        section9_languageGotchas();
        section10_annotationsAndReflection();
        System.out.printf("%nAll %d checks passed.%n", passed);
    }

    // =========================================================================
    // 1. OBJECT-ORIENTED PROGRAMMING
    // =========================================================================

    /**
     * An INTERFACE defines a capability, a contract with no state.
     * Since Java 8 interfaces can have:
     *   - default methods: an inherited implementation that classes MAY override.
     *     Defaults let libraries evolve interfaces without breaking implementors.
     *   - static methods: utilities that belong to the interface itself.
     * Since Java 9 they can also have:
     *   - private methods: helpers shared by default methods, hidden from callers.
     */
    interface Borrowable {
        int loanPeriodDays();                              // abstract: implementors must provide it

        default double lateFee(int daysLate) {             // default: shared behavior
            return roundCents(Math.max(0, daysLate) * dailyRate());
        }

        default double dailyRate() {
            return 0.25;
        }

        static boolean isOverdue(int daysBorrowed, Borrowable item) {   // static utility
            return daysBorrowed > item.loanPeriodDays();
        }

        private static double roundCents(double v) {      // private helper (Java 9+)
            return Math.round(v * 100) / 100.0;
        }
    }

    interface Reservable {
        default String describe() { return "reservable"; }
    }

    interface Displayable {
        default String describe() { return "displayable"; }
    }

    /**
     * An ABSTRACT CLASS can hold STATE and constructors, and can mix concrete
     * and abstract methods. Use one when subclasses share data and code. Use an
     * interface when unrelated classes share a capability.
     * A class can EXTEND only one class but IMPLEMENT many interfaces.
     */
    abstract static class LibraryItem implements Borrowable, Comparable<LibraryItem> {
        private final String id;
        private final String title;

        protected LibraryItem(String id, String title) {
            this.id = Objects.requireNonNull(id);
            this.title = Objects.requireNonNull(title);
        }

        String id() { return id; }
        String title() { return title; }

        /** Template method: the algorithm skeleton lives here, subclasses fill in steps. */
        final String label() {                                // final: subclasses can't change it
            return "[" + kind() + "] " + title + " (" + loanPeriodDays() + " days)";
        }

        protected abstract String kind();

        /** Subclasses override this with a MORE SPECIFIC return type (covariant return). */
        abstract LibraryItem copyWithId(String newId);

        // Natural ordering = by id. Comparable gives a class ONE "natural" order.
        // Other orderings are supplied from outside with a Comparator.
        @Override
        public int compareTo(LibraryItem other) {
            return id.compareTo(other.id);
        }

        /*
         * equals/hashCode CONTRACT:
         *   - equal objects MUST have equal hash codes
         *   - equals must be reflexive, symmetric, transitive, consistent, and
         *     x.equals(null) must be false
         * Here identity is the id. getClass() (not instanceof) means a Book never
         * equals a Dvd, even with the same id; that keeps equals symmetric
         * across the hierarchy.
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return id.equals(((LibraryItem) o).id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public String toString() {
            return kind() + ":" + id;
        }
    }

    static class Book extends LibraryItem implements Reservable, Displayable {
        private final int pages;

        Book(String id, String title, int pages) {
            super(id, title);                                 // must be the first statement
            this.pages = pages;
        }

        int pages() { return pages; }

        @Override public int loanPeriodDays() { return 21; }
        @Override protected String kind() { return "Book"; }

        // COVARIANT RETURN TYPE: the parent declares LibraryItem; overriding with
        // Book is allowed, so callers holding a Book need no cast.
        @Override
        Book copyWithId(String newId) {
            return new Book(newId, title(), pages);
        }

        /*
         * DIAMOND CONFLICT: Reservable and Displayable BOTH supply a default
         * describe(). The compiler refuses to guess, so the class must override it.
         * `Interface.super.method()` calls a specific parent's default.
         */
        @Override
        public String describe() {
            return Reservable.super.describe() + "+" + Displayable.super.describe();
        }
    }

    static class Dvd extends LibraryItem {
        Dvd(String id, String title) { super(id, title); }

        @Override public int loanPeriodDays() { return 7; }
        @Override public double dailyRate() { return 1.00; }  // overrides the interface default
        @Override protected String kind() { return "DVD"; }
        @Override Dvd copyWithId(String newId) { return new Dvd(newId, title()); }
    }

    /*
     * OVERLOADING vs OVERRIDING
     *   Overloading = same name, DIFFERENT parameter lists, chosen at COMPILE
     *                 time from the STATIC (declared) types of the arguments.
     *   Overriding  = same signature in a subclass, chosen at RUNTIME from the
     *                 ACTUAL object type (dynamic dispatch / polymorphism).
     */
    static String classify(LibraryItem item) { return "some item"; }
    static String classify(Book book) { return "a book"; }

    static void section1_oop() {
        section("1. OOP: interfaces, abstract classes, polymorphism");

        List<LibraryItem> items = List.of(new Book("B2", "Dune", 412), new Dvd("D1", "Alien"), new Book("B1", "Emma", 320));

        // POLYMORPHISM: one call site, behavior chosen by each object's real class
        List<String> labels = items.stream().map(LibraryItem::label).toList();
        check("polymorphic label()", labels,
                List.of("[Book] Dune (21 days)", "[DVD] Alien (7 days)", "[Book] Emma (21 days)"));

        check("default method lateFee (book)", items.get(0).lateFee(4), 1.0);
        check("overridden default (dvd)", items.get(1).lateFee(4), 4.0);
        check("static interface method", Borrowable.isOverdue(10, items.get(1)), true);

        // Comparable gives the natural order (by id)
        check("natural order (Comparable)", new TreeSet<>(items).stream().map(LibraryItem::id).toList(),
                List.of("B1", "B2", "D1"));

        Book book = new Book("B9", "Ulysses", 730);
        Book copy = book.copyWithId("B10");            // covariant return: no cast needed
        check("covariant return keeps type", copy.pages(), 730);
        check("diamond default resolved", book.describe(), "reservable+displayable");

        // Overloading is resolved at COMPILE time from the DECLARED type:
        LibraryItem declaredAsItem = book;
        check("overload uses static type", classify(declaredAsItem), "some item");
        check("overload with Book type", classify(book), "a book");

        // equals uses the id AND the exact class
        check("equals by id", new Book("X", "a", 1).equals(new Book("X", "b", 2)), true);
        check("Book never equals Dvd", new Book("X", "a", 1).equals(new Dvd("X", "a")), false);
    }

    // =========================================================================
    // 2. OBJECT INITIALIZATION ORDER
    // =========================================================================
    //
    // When `new Child()` runs for the FIRST time:
    //   1. Parent static initializers   (once per class, when the class is loaded)
    //   2. Child static initializers
    // Then for EVERY new instance:
    //   3. Parent field initializers + instance blocks
    //   4. Parent constructor body
    //   5. Child field initializers + instance blocks
    //   6. Child constructor body
    //
    // DANGER: if the parent constructor calls an OVERRIDABLE method, the child's
    // override runs BEFORE step 5, while the child's fields are still default
    // values (null / 0). Effective Java Item 19: "constructors must not invoke
    // overridable methods".
    // =========================================================================

    static final List<String> INIT_LOG = new ArrayList<>();

    static class Parent {
        static { INIT_LOG.add("Parent static"); }
        { INIT_LOG.add("Parent instance block"); }

        Parent() {
            INIT_LOG.add("Parent constructor");
            INIT_LOG.add("hook sees: " + describeSelf());     // overridable call from a constructor!
        }

        String describeSelf() { return "parent"; }
    }

    static class Child extends Parent {
        static { INIT_LOG.add("Child static"); }
        // Not assigned yet when Parent() runs!
        // SUBTLETY: if this were `private final String name = "child-name";`
        // it would be a COMPILE-TIME CONSTANT. The compiler would inline the
        // literal at every use, and the trap would be hidden. Any non-constant
        // initializer (a method call, `new`, or a non-final field) shows the
        // real behavior.
        private final String name = String.join("-", "child", "name");
        { INIT_LOG.add("Child instance block"); }

        Child() {
            super();
            INIT_LOG.add("Child constructor");
        }

        @Override
        String describeSelf() { return "name=" + name; }
    }

    static void section2_initializationOrder() {
        section("2. Initialization order + the constructor-hook trap");

        new Child();
        check("first construction", List.copyOf(INIT_LOG), List.of(
                "Parent static", "Child static",
                "Parent instance block", "Parent constructor",
                "hook sees: name=null",            // <- the trap: the field is still null
                "Child instance block", "Child constructor"));

        INIT_LOG.clear();
        new Child();
        check("second: static blocks don't rerun", INIT_LOG.getFirst(), "Parent instance block");
    }

    // =========================================================================
    // 3. NESTED CLASSES + CUSTOM ITERABLE
    // =========================================================================
    //
    //   STATIC NESTED  `static class X`: no link to an outer instance. Used for
    //                  helpers and builders. PREFER THIS by default.
    //   INNER          `class X`: holds a hidden reference to its outer instance
    //                  (Outer.this), so it can read outer fields. Beware: it
    //                  keeps the outer object alive (possible memory leaks).
    //   LOCAL          declared inside a method; can capture effectively-final
    //                  local variables.
    //   ANONYMOUS      declared and instantiated in one expression; for one-off
    //                  implementations (mostly replaced by lambdas today).
    // =========================================================================

    /** A numeric range usable in for-each loops, because it implements Iterable. */
    static final class Range implements Iterable<Integer> {
        private final int start, end, step;

        Range(int start, int end, int step) {
            if (step <= 0) throw new IllegalArgumentException("step must be positive");
            this.start = start;
            this.end = end;
            this.step = step;
        }

        @Override
        public Iterator<Integer> iterator() {
            return new RangeIterator();          // the inner class gets Range.this implicitly
        }

        /** INNER class: reads start/end/step of the enclosing Range instance. */
        private class RangeIterator implements Iterator<Integer> {
            private int next = start;           // same as Range.this.start

            @Override public boolean hasNext() { return next < end; }

            @Override public Integer next() {
                if (!hasNext()) throw new NoSuchElementException();   // the Iterator contract
                int current = next;
                next += step;
                return current;
            }
        }
    }

    /** Outer class with an inner class that needs an outer INSTANCE to exist. */
    static class Shelf {
        private final String location;
        Shelf(String location) { this.location = location; }

        class Slot {                                          // inner (non-static)
            private final int number;
            Slot(int number) { this.number = number; }
            String address() { return location + "-" + number; }   // reads the outer field
        }
    }

    static void section3_nestedClasses() {
        section("3. Nested classes + custom Iterable");

        List<Integer> evens = new ArrayList<>();
        for (int n : new Range(0, 10, 2)) evens.add(n);      // for-each works on any Iterable
        check("custom Iterable in for-each", evens, List.of(0, 2, 4, 6, 8));

        Shelf shelf = new Shelf("A3");
        Shelf.Slot slot = shelf.new Slot(7);                  // note the syntax: outer.new Inner()
        check("inner class reads outer field", slot.address(), "A3-7");

        // LOCAL class: lives in this method and captures a local variable
        String prefix = "ISBN-";                              // effectively final
        class IsbnFormatter {
            String format(String raw) { return prefix + raw.replace("-", ""); }
        }
        check("local class captures local var", new IsbnFormatter().format("978-0-13"), "ISBN-978013");

        // ANONYMOUS class vs lambda: both implement Comparator<String>
        Comparator<String> byLengthAnon = new Comparator<>() {
            @Override public int compare(String a, String b) { return Integer.compare(a.length(), b.length()); }
        };
        Comparator<String> byLengthLambda = Comparator.comparingInt(String::length);
        List<String> words = new ArrayList<>(List.of("ccc", "a", "bb"));
        words.sort(byLengthAnon);
        check("anonymous Comparator", words, List.of("a", "bb", "ccc"));
        words.sort(byLengthLambda.reversed());
        check("lambda Comparator reversed", words, List.of("ccc", "bb", "a"));
    }

    // =========================================================================
    // 4. ENUMS
    // =========================================================================
    //
    // A Java enum is a full class: it can have fields, constructors (always
    // private), methods, and even a separate method body PER CONSTANT. Enums
    // are type-safe, singletons by design, Serializable, and usable in switch.
    // EnumSet / EnumMap are bit-vector / array based, so much faster than
    // HashSet/HashMap for enum keys.
    // =========================================================================

    enum MembershipLevel {
        BASIC(2, 0.0) {
            @Override String perk() { return "none"; }
        },
        SILVER(5, 0.10) {
            @Override String perk() { return "free holds"; }
        },
        GOLD(10, 0.25) {
            @Override String perk() { return "free holds + no late fees on weekends"; }
        };

        private final int maxLoans;
        private final double feeDiscount;

        MembershipLevel(int maxLoans, double feeDiscount) {   // implicitly private
            this.maxLoans = maxLoans;
            this.feeDiscount = feeDiscount;
        }

        int maxLoans() { return maxLoans; }

        double discountedFee(double fee) {
            return Math.round(fee * (1 - feeDiscount) * 100) / 100.0;
        }

        /** CONSTANT-SPECIFIC BODY: each constant above implements this differently. */
        abstract String perk();

        MembershipLevel next() {
            MembershipLevel[] all = values();
            return all[Math.min(ordinal() + 1, all.length - 1)];
        }
    }

    enum Weekday { MON, TUE, WED, THU, FRI, SAT, SUN }

    static void section4_enums() {
        section("4. Enums: fields, behavior, EnumSet, EnumMap");

        check("enum field", MembershipLevel.GOLD.maxLoans(), 10);
        check("enum method", MembershipLevel.SILVER.discountedFee(10.0), 9.0);
        check("constant-specific body", MembershipLevel.SILVER.perk(), "free holds");
        check("valueOf + ordinal", MembershipLevel.valueOf("GOLD").ordinal(), 2);
        check("next() caps at GOLD", MembershipLevel.GOLD.next(), MembershipLevel.GOLD);

        EnumSet<Weekday> weekend = EnumSet.of(Weekday.SAT, Weekday.SUN);
        EnumSet<Weekday> workdays = EnumSet.complementOf(weekend);
        check("EnumSet.complementOf", workdays, EnumSet.range(Weekday.MON, Weekday.FRI));

        EnumMap<Weekday, String> hours = new EnumMap<>(Weekday.class);  // always iterates in enum order
        hours.put(Weekday.SUN, "closed");
        hours.put(Weekday.MON, "9-17");
        hours.put(Weekday.SAT, "10-14");
        check("EnumMap keeps enum order", List.copyOf(hours.keySet()), List.of(Weekday.MON, Weekday.SAT, Weekday.SUN));

        // switch expression on an enum: exhaustive, so no default needed
        String type = switch (Weekday.SAT) {
            case SAT, SUN -> "weekend";
            case MON, TUE, WED, THU, FRI -> "weekday";
        };
        check("switch on enum", type, "weekend");
    }

    // =========================================================================
    // 5. GENERICS AND PARAMETERIZED TYPES
    // =========================================================================

    /** A generic interface: every entity exposes an id of SOME type ID. */
    interface Identifiable<ID> {
        ID getId();
    }

    record Member(Integer getId, String name, MembershipLevel level) implements Identifiable<Integer> {}

    record Isbn(String value) implements Comparable<Isbn> {
        public int compareTo(Isbn o) { return value.compareTo(o.value); }
    }

    record Title(Isbn getId, String name, int year) implements Identifiable<Isbn> {}

    /**
     * GENERIC CLASS WITH BOUNDED TYPE PARAMETERS
     *
     *   T  extends Identifiable<ID>   T must be an entity whose id type is ID
     *   ID extends Comparable<ID>     ids must be sortable (stored in a TreeMap)
     *
     * One implementation now works type-safely for Members (Integer ids) AND
     * Titles (Isbn ids). The compiler rejects mixing them; no casts anywhere.
     */
    static class Repository<T extends Identifiable<ID>, ID extends Comparable<ID>> {
        private final NavigableMap<ID, T> store = new TreeMap<>();

        void save(T entity) {
            store.put(entity.getId(), entity);
        }

        Optional<T> findById(ID id) {
            return Optional.ofNullable(store.get(id));
        }

        /**
         * `Predicate<? super T>` instead of Predicate<T>: callers may pass a
         * more GENERAL predicate (e.g. Predicate<Object> or one written for a
         * supertype). This is the "consumer" half of PECS, explained below.
         */
        List<T> findAll(Predicate<? super T> filter) {
            return store.values().stream().filter(filter).toList();
        }

        /** A GENERIC METHOD inside a generic class: R is chosen per call. */
        <R> List<R> project(Function<? super T, ? extends R> mapper) {
            return store.values().stream().<R>map(mapper).toList();
        }

        int size() { return store.size(); }
    }

    /**
     * GENERIC METHOD with a RECURSIVE BOUND: <T extends Comparable<? super T>>
     *
     * Why `? super T` and not plain Comparable<T>? Picture class Parent
     * implements Comparable<Parent> and class Child extends Parent. Child is
     * Comparable<Parent>, NOT Comparable<Child>. With `? super T`, max()
     * still works for a List<Child>. (Collections.max uses this same signature.)
     */
    static <T extends Comparable<? super T>> T max(Collection<? extends T> items) {
        Iterator<? extends T> it = items.iterator();
        T best = it.next();
        while (it.hasNext()) {
            T candidate = it.next();
            if (candidate.compareTo(best) > 0) best = candidate;
        }
        return best;
    }

    /**
     * PECS: "Producer Extends, Consumer Super"
     *   src is a PRODUCER (we read T values out of it)  -> List<? extends T>
     *   dst is a CONSUMER (we write T values into it)   -> List<? super T>
     * So you can copy a List<Integer> into a List<Number> or a List<Object>.
     * (Collections.copy has this signature.)
     */
    static <T> void copyInto(List<? super T> dst, List<? extends T> src) {
        for (T item : src) dst.add(item);
        // src.add(...) would NOT compile: with ? extends T we don't know the real
        // element type, so nothing (except null) can be safely added.
    }

    /** UNBOUNDED WILDCARD: we don't care about the element type at all. */
    static int countNulls(Collection<?> items) {
        int n = 0;
        for (Object o : items) if (o == null) n++;
        return n;
    }

    /**
     * MULTIPLE BOUNDS: T must be BOTH a Number (for doubleValue) AND
     * Comparable to itself. Syntax: the class first, then interfaces, joined
     * with &.
     */
    static <T extends Number & Comparable<T>> double sumAtLeast(List<T> values, T threshold) {
        double sum = 0;
        for (T v : values) {
            if (v.compareTo(threshold) >= 0) sum += v.doubleValue();
        }
        return sum;
    }

    /**
     * RECURSIVE GENERICS ("curiously recurring template pattern"): a builder
     * hierarchy whose inherited methods return the SUBCLASS type, so chained
     * calls keep working: new BookBuilder().title("x").pages(10)
     * Without it, title() would return ItemBuilder and pages() would not compile.
     */
    abstract static class ItemBuilder<B extends ItemBuilder<B>> {
        protected String id;
        protected String title;

        B id(String id) { this.id = id; return self(); }
        B title(String title) { this.title = title; return self(); }

        protected abstract B self();
    }

    static final class BookBuilder extends ItemBuilder<BookBuilder> {
        private int pages;

        BookBuilder pages(int pages) { this.pages = pages; return this; }

        @Override protected BookBuilder self() { return this; }

        Book build() { return new Book(id, title, pages); }
    }

    /**
     * TYPE-SAFE HETEROGENEOUS CONTAINER (Effective Java Item 33)
     * A normal Map<K, V> holds ONE value type. Here the KEY is a Class<T> and
     * it carries the value's type, so each entry can have a different type,
     * still with no unchecked casts for callers. Class.cast() checks at runtime.
     */
    static final class LibrarySettings {
        private final Map<Class<?>, Object> values = new HashMap<>();

        <T> void put(Class<T> type, T value) {
            values.put(Objects.requireNonNull(type), type.cast(value));
        }

        <T> T get(Class<T> type) {
            return type.cast(values.get(type));
        }
    }

    /**
     * TYPE TOKENS: because of erasure, `new T()` is impossible (T doesn't
     * exist at runtime). Passing a Class<T> or Supplier<T> brings the type
     * back at runtime.
     */
    static <T> List<T> createMany(Supplier<? extends T> factory, int count) {
        List<T> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) list.add(factory.get());
        return list;
    }

    static <T> T[] toTypedArray(List<T> list, Class<T> type) {
        // `new T[n]` is illegal. java.lang.reflect.Array creates it from the token.
        @SuppressWarnings("unchecked")
        T[] array = (T[]) java.lang.reflect.Array.newInstance(type, list.size());
        return list.toArray(array);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void section5_generics() {
        section("5. Generics: bounds, wildcards, PECS, erasure, type tokens");

        // --- generic repository working for two different entity/id types
        Repository<Member, Integer> members = new Repository<>();   // diamond operator infers types
        members.save(new Member(2, "Bea", MembershipLevel.GOLD));
        members.save(new Member(1, "Al", MembershipLevel.BASIC));
        members.save(new Member(3, "Cy", MembershipLevel.SILVER));

        Repository<Title, Isbn> titles = new Repository<>();
        titles.save(new Title(new Isbn("978-1"), "Dune", 1965));

        check("findById", members.findById(2).map(Member::name).orElse("?"), "Bea");
        check("findById missing", members.findById(99).isPresent(), false);
        check("TreeMap keeps ids sorted", members.project(Member::name), List.of("Al", "Bea", "Cy"));
        Predicate<Object> notNull = Objects::nonNull;               // a Predicate<Object>...
        check("Predicate<? super T> accepts Predicate<Object>", members.findAll(notNull).size(), 3);
        check("repo with Isbn ids", titles.findById(new Isbn("978-1")).orElseThrow().name(), "Dune");
        // members.save(new Title(...));   // <- COMPILE ERROR: type safety at work

        // --- bounded generic method
        check("max of Integers", max(List.of(3, 9, 4)), 9);
        check("max of Strings", max(Set.of("pear", "apple", "zucchini")), "zucchini");

        // --- PECS
        List<Number> numbers = new ArrayList<>(List.of(0.5));
        List<Object> objects = new ArrayList<>();
        List<Integer> ints = List.of(1, 2, 3);
        copyInto(numbers, ints);          // T = Integer, dst is List<? super Integer>
        copyInto(objects, ints);
        check("PECS: Integers into List<Number>", numbers, List.of(0.5, 1, 2, 3));
        check("PECS: Integers into List<Object>", objects, List.of(1, 2, 3));

        check("unbounded wildcard", countNulls(Arrays.asList("a", null, 3, null)), 2);
        check("multiple bounds", sumAtLeast(List.of(1, 5, 10), 5), 15.0);

        // --- recursive generics: chaining keeps the subclass type
        Book built = new BookBuilder().title("Neuromancer").id("B7").pages(271).build();
        check("self-typed builder", built.label(), "[Book] Neuromancer (21 days)");

        // --- type erasure: generic types disappear at runtime
        check("erasure: same runtime class",
                new ArrayList<String>().getClass() == new ArrayList<Integer>().getClass(), true);

        // --- HEAP POLLUTION via raw types: the compiler only WARNS, and the
        // failure surfaces LATER, far from the bug, as a ClassCastException.
        List<String> strings = new ArrayList<>();
        List raw = strings;                 // raw type: generic checks switched off
        raw.add(42);                        // compiles (with a warning). Now a String list holds an Integer!
        String outcome;
        try {
            String s = strings.get(0);      // the compiler-inserted cast to String fails HERE
            outcome = s;
        } catch (ClassCastException e) {
            outcome = "ClassCastException";
        }
        check("raw types cause heap pollution", outcome, "ClassCastException");

        // --- type-safe heterogeneous container
        LibrarySettings settings = new LibrarySettings();
        settings.put(Integer.class, 14);
        settings.put(String.class, "Central Library");
        settings.put(MembershipLevel.class, MembershipLevel.SILVER);
        int loanDays = settings.get(Integer.class);                // no cast needed
        check("heterogeneous container", loanDays + " " + settings.get(String.class), "14 Central Library");

        // --- type tokens
        List<StringBuilder> builders = createMany(StringBuilder::new, 3);
        check("Supplier as type token", builders.size(), 3);
        String[] arr = toTypedArray(List.of("x", "y"), String.class);
        check("generic array via Class token", arr.getClass().getSimpleName(), "String[]");
    }

    // =========================================================================
    // 6. COLLECTIONS FRAMEWORK
    // =========================================================================
    //
    //  Interface  Implementation    Order              Typical cost
    //  ---------  ----------------  -----------------  ------------------------
    //  List       ArrayList         insertion          get O(1), add-end O(1)*
    //             LinkedList        insertion          get O(n), add/remove at
    //                                                  an iterator O(1)
    //  Set        HashSet           none               add/contains O(1)
    //             LinkedHashSet     insertion          O(1)
    //             TreeSet           sorted             O(log n), NavigableSet
    //  Map        HashMap           none               O(1)
    //             LinkedHashMap     insertion/access   O(1) (LRU caches)
    //             TreeMap           sorted keys        O(log n), NavigableMap
    //             EnumMap           enum order         O(1), array based
    //  Queue      ArrayDeque        FIFO / LIFO        O(1) (stack AND queue)
    //             PriorityQueue     by priority        offer/poll O(log n)
    //  (* amortized)
    //
    //  Rule of thumb: ArrayList, HashMap, HashSet and ArrayDeque are the right
    //  defaults. Reach for others when you need ORDER or SORTING.
    // =========================================================================

    /** LRU cache in about 10 lines: LinkedHashMap in ACCESS order + removeEldestEntry. */
    static final class LruCache<K, V> extends LinkedHashMap<K, V> {
        // LinkedHashMap is Serializable, so subclasses should declare a serialVersionUID
        // (otherwise one is computed from the class shape and changes with any edit).
        private static final long serialVersionUID = 1L;
        private final int capacity;

        LruCache(int capacity) {
            super(16, 0.75f, true);           // accessOrder = true: get() moves an entry to the end
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > capacity;          // evict the least-recently used entry
        }
    }

    /** A key class that BREAKS the contract: equals without hashCode. */
    @SuppressWarnings("overrides")   // intentional: this class demonstrates the bug the compiler warns about
    static final class BadKey {
        final String code;
        BadKey(String code) { this.code = code; }

        @Override public boolean equals(Object o) {
            return o instanceof BadKey other && code.equals(other.code);
        }
        // hashCode() NOT overridden: equal objects get different (identity) hashes
    }

    record Task(String name, int priority) {}

    static void section6_collections() {
        section("6. Collections: choosing, navigating, and pitfalls");

        // ---------------------------------------------------------- Lists
        List<String> fixedSize = Arrays.asList("a", "b", "c");   // backed by the array: set OK, add NOT
        fixedSize.set(0, "A");
        check("Arrays.asList is fixed-size",
                throwsName(() -> fixedSize.add("d")), "UnsupportedOperationException");
        check("List.of is fully immutable",
                throwsName(() -> List.of("x").set(0, "y")), "UnsupportedOperationException");

        List<Integer> numbers = new ArrayList<>(List.of(1, 2, 3, 4, 5, 6));
        List<Integer> window = numbers.subList(1, 4);   // a VIEW, not a copy
        window.clear();                                   // removes 2, 3, 4 from the ORIGINAL list
        check("subList is a view", numbers, List.of(1, 5, 6));

        // FAIL-FAST ITERATORS: changing a collection while for-each iterates it
        List<String> loans = new ArrayList<>(List.of("ok", "overdue", "ok", "overdue"));
        check("modify during for-each",
                throwsName(() -> { for (String l : loans) if (l.equals("overdue")) loans.remove(l); }),
                "ConcurrentModificationException");
        // Correct: Iterator.remove() or removeIf
        Iterator<String> it = loans.iterator();
        while (it.hasNext()) if (it.next().equals("overdue")) it.remove();
        check("Iterator.remove is safe", loans, List.of("ok", "ok"));

        // ListIterator can go BACKWARDS and REPLACE elements in place
        List<String> titles = new ArrayList<>(List.of("dune", "emma"));
        for (ListIterator<String> li = titles.listIterator(); li.hasNext(); ) {
            li.set(li.next().toUpperCase());
        }
        check("ListIterator.set", titles, List.of("DUNE", "EMMA"));

        // ----------------------------------------------------------- Sets
        List<String> input = List.of("pear", "apple", "fig", "apple", "banana");
        check("LinkedHashSet keeps insertion order", List.copyOf(new LinkedHashSet<>(input)),
                List.of("pear", "apple", "fig", "banana"));

        TreeSet<String> sorted = new TreeSet<>(Comparator.comparingInt(String::length)
                .thenComparing(Comparator.naturalOrder()));
        sorted.addAll(input);
        check("TreeSet with a Comparator", List.copyOf(sorted), List.of("fig", "pear", "apple", "banana"));
        check("NavigableSet.ceiling", sorted.ceiling("zzzz"), "apple");    // smallest element >= "zzzz" in this order
        check("NavigableSet.headSet", List.copyOf(sorted.headSet("apple")), List.of("fig", "pear"));

        // Sorted collections use compareTo, NOT equals!
        // BigDecimal("1.0").equals("1.00") is false, but compareTo == 0.
        check("HashSet uses equals", new HashSet<>(List.of(new BigDecimal("1.0"), new BigDecimal("1.00"))).size(), 2);
        check("TreeSet uses compareTo", new TreeSet<>(List.of(new BigDecimal("1.0"), new BigDecimal("1.00"))).size(), 1);

        // --------------------------------------------- equals/hashCode contract
        Map<BadKey, String> broken = new HashMap<>();
        broken.put(new BadKey("B1"), "Dune");
        check("missing hashCode breaks HashMap", broken.get(new BadKey("B1")), null);

        Map<Book, String> working = new HashMap<>();
        working.put(new Book("B1", "Dune", 412), "shelf A");
        check("proper equals/hashCode works", working.get(new Book("B1", "other title", 1)), "shelf A");

        // ----------------------------------------------------- Map idioms
        String text = "to be or not to be";
        Map<String, Integer> counts = new TreeMap<>();
        for (String w : text.split(" ")) counts.merge(w, 1, Integer::sum);     // insert 1, or add 1
        check("merge for counting", counts, Map.of("be", 2, "not", 1, "or", 1, "to", 2));

        Map<MembershipLevel, List<String>> byLevel = new EnumMap<>(MembershipLevel.class);
        byLevel.computeIfAbsent(MembershipLevel.GOLD, k -> new ArrayList<>()).add("Bea");   // multimap idiom
        byLevel.computeIfAbsent(MembershipLevel.GOLD, k -> new ArrayList<>()).add("Dee");
        check("computeIfAbsent multimap", byLevel.get(MembershipLevel.GOLD), List.of("Bea", "Dee"));
        check("getOrDefault", byLevel.getOrDefault(MembershipLevel.BASIC, List.of()), List.of());

        Map<String, Integer> stock = new HashMap<>(Map.of("dune", 3));
        stock.computeIfPresent("dune", (k, v) -> v - 3 == 0 ? null : v - 3);   // returning null REMOVES the entry
        check("computeIfPresent -> null removes", stock.containsKey("dune"), false);

        // ------------------------------------------ NavigableMap (TreeMap)
        // Late-fee tiers: look up the tier for "days late" with floorEntry
        NavigableMap<Integer, Double> feeTiers = new TreeMap<>(Map.of(0, 0.0, 1, 0.25, 8, 0.50, 31, 1.00));
        check("floorEntry tier lookup (10 days)", feeTiers.floorEntry(10).getValue(), 0.50);
        check("ceilingKey", feeTiers.ceilingKey(9), 31);
        check("headMap(8) view", List.copyOf(feeTiers.headMap(8).keySet()), List.of(0, 1));
        check("descendingMap first key", feeTiers.descendingMap().firstKey(), 31);

        // ------------------------------------------------------ LRU cache
        LruCache<String, String> cache = new LruCache<>(2);
        cache.put("a", "A");
        cache.put("b", "B");
        cache.get("a");                 // "a" is now most-recently used
        cache.put("c", "C");            // evicts "b", the least recently used
        check("LRU eviction", List.copyOf(cache.keySet()), List.of("a", "c"));

        // ---------------------------------------- Queues: priority + deque
        PriorityQueue<Task> queue = new PriorityQueue<>(Comparator.comparingInt(Task::priority).reversed());
        queue.addAll(List.of(new Task("shelve", 1), new Task("repair", 5), new Task("catalog", 3)));
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) order.add(queue.poll().name());   // NOTE: iterating a PQ is NOT sorted; poll is
        check("PriorityQueue poll order", order, List.of("repair", "catalog", "shelve"));

        // Top-K with a bounded MIN-heap: O(n log k) instead of sorting everything
        PriorityQueue<Integer> topK = new PriorityQueue<>();
        for (int n : List.of(5, 1, 9, 3, 7, 8)) {
            topK.offer(n);
            if (topK.size() > 3) topK.poll();          // drop the smallest
        }
        check("top-3 via min-heap", topK.stream().sorted(Comparator.reverseOrder()).toList(), List.of(9, 8, 7));

        // ArrayDeque as a STACK: balanced brackets (prefer it over the legacy Stack class)
        check("ArrayDeque stack: balanced", isBalanced("{[()()]}"), true);
        check("ArrayDeque stack: unbalanced", isBalanced("([)]"), false);

        // ------------------------------------------- views vs copies
        List<String> source = new ArrayList<>(List.of("x"));
        List<String> view = Collections.unmodifiableList(source);   // read-only VIEW: sees later changes
        List<String> snapshot = List.copyOf(source);                // independent immutable COPY
        source.add("y");
        check("unmodifiableList is a live view", view, List.of("x", "y"));
        check("List.copyOf is a snapshot", snapshot, List.of("x"));

        // ------------------------------------------- Collections utilities
        List<Integer> nums = new ArrayList<>(List.of(4, 2, 9, 2));
        Collections.sort(nums);
        check("Collections.binarySearch", Collections.binarySearch(nums, 9), 3);
        check("Collections.frequency", Collections.frequency(nums, 2), 2);
        check("Collections.disjoint", Collections.disjoint(nums, List.of(1, 3)), true);
        check("Collections.nCopies", String.join("", Collections.nCopies(3, "ab")), "ababab");

        // IdentityHashMap compares keys with ==, not equals
        String k1 = new String("key"), k2 = new String("key");
        Map<String, Integer> identity = new IdentityHashMap<>();
        identity.put(k1, 1);
        identity.put(k2, 2);
        check("IdentityHashMap uses ==", identity.size(), 2);
    }

    static boolean isBalanced(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        Map<Character, Character> pairs = Map.of(')', '(', ']', '[', '}', '{');
        for (char c : s.toCharArray()) {
            if (pairs.containsValue(c)) stack.push(c);
            else if (pairs.containsKey(c)) {
                if (stack.isEmpty() || stack.pop() != pairs.get(c)) return false;
            }
        }
        return stack.isEmpty();
    }

    // =========================================================================
    // 7. EXCEPTIONS
    // =========================================================================
    //
    //   Throwable
    //    ├── Error                 serious JVM problems (OutOfMemoryError). Don't catch.
    //    └── Exception             CHECKED: the compiler forces callers to handle
    //         │                    or declare it. Use for RECOVERABLE conditions
    //         │                    the caller should think about.
    //         └── RuntimeException UNCHECKED: programming errors (NPE, illegal
    //                              arguments, bad state). Not forced on callers.
    // =========================================================================

    /** Custom CHECKED exception carrying domain data. */
    static class ItemUnavailableException extends Exception {
        private static final long serialVersionUID = 1L;     // exceptions are Serializable
        private final String itemId;

        ItemUnavailableException(String itemId, String message) {
            super(message);
            this.itemId = itemId;
        }

        String itemId() { return itemId; }
    }

    /** Custom UNCHECKED exception that WRAPS a low-level cause (exception chaining). */
    static class CatalogException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        CatalogException(String message, Throwable cause) {
            super(message, cause);                 // keep the cause! never lose the root error
        }
    }

    static String checkout(Map<String, Integer> copies, String itemId) throws ItemUnavailableException {
        int available = copies.getOrDefault(itemId, 0);
        if (available == 0) throw new ItemUnavailableException(itemId, "No copies left of " + itemId);
        copies.put(itemId, available - 1);
        return "checked out " + itemId;
    }

    /** An AutoCloseable resource that records events, and may fail on close. */
    static final class TrackedResource implements AutoCloseable {
        private final String name;
        private final List<String> log;
        private final boolean failOnClose;

        TrackedResource(String name, List<String> log, boolean failOnClose) {
            this.name = name;
            this.log = log;
            this.failOnClose = failOnClose;
            log.add("open " + name);
        }

        @Override
        public void close() {
            log.add("close " + name);
            if (failOnClose) throw new IllegalStateException("close failed: " + name);
        }
    }

    @SuppressWarnings("finally")
    static int finallyReturnValue() {
        int x = 1;
        try {
            return x;          // the return VALUE (1) is computed and saved here...
        } finally {
            x = 99;            // ...so changing x afterwards doesn't affect what's returned
        }
    }

    @SuppressWarnings("try")   // the resources exist only to show open/close order
    static void section7_exceptions() {
        section("7. Exceptions: checked, chaining, try-with-resources");

        Map<String, Integer> copies = new HashMap<>(Map.of("B1", 1));
        String result;
        try {
            checkout(copies, "B1");
            result = checkout(copies, "B1");       // second checkout fails
        } catch (ItemUnavailableException e) {     // the compiler FORCES this catch (or a throws clause)
            result = "unavailable: " + e.itemId();
        }
        check("custom checked exception", result, "unavailable: B1");

        // EXCEPTION CHAINING: translate a low-level error into a domain error, keeping the cause
        CatalogException chained = null;
        try {
            try {
                Integer.parseInt("12x");                       // low-level failure
            } catch (NumberFormatException e) {
                throw new CatalogException("Corrupt catalog record", e);
            }
        } catch (CatalogException e) {
            chained = e;
        }
        check("exception chaining keeps the cause", chained.getCause().getClass().getSimpleName(), "NumberFormatException");

        // TRY-WITH-RESOURCES: resources are closed automatically, in REVERSE order of opening
        List<String> log = new ArrayList<>();
        try (var db = new TrackedResource("db", log, false);
             var file = new TrackedResource("file", log, false)) {
            log.add("work");
        }
        check("closed in reverse order", log, List.of("open db", "open file", "work", "close file", "close db"));

        // SUPPRESSED EXCEPTIONS: if the body throws AND close() throws, the body's
        // exception wins, and the close failure is attached as "suppressed"
        // instead of being lost (which is what the old try/finally style did).
        List<String> log2 = new ArrayList<>();
        Exception primary = null;
        try (var r = new TrackedResource("socket", log2, true)) {
            throw new IllegalArgumentException("body failed");
        } catch (IllegalArgumentException e) {
            primary = e;
        }
        check("primary exception kept", primary.getMessage(), "body failed");
        check("close() failure is suppressed", primary.getSuppressed()[0].getMessage(), "close failed: socket");

        // MULTI-CATCH: one handler for unrelated exception types
        String handled;
        try {
            Object o = List.of(1).get(5);
        } catch (IndexOutOfBoundsException | ClassCastException e) {
            handled = e.getClass().getSimpleName();
            check("multi-catch", handled, "IndexOutOfBoundsException");
        }

        check("finally can't change a computed return", finallyReturnValue(), 1);
    }

    // =========================================================================
    // 8. FUNCTIONAL PROGRAMMING
    // =========================================================================

    /**
     * A custom FUNCTIONAL INTERFACE: exactly one abstract method. Lambdas and
     * method references can implement it. @FunctionalInterface makes the
     * compiler enforce the single-abstract-method rule.
     */
    @FunctionalInterface
    interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);

        default <V> TriFunction<A, B, C, V> andThen(Function<? super R, ? extends V> after) {
            return (a, b, c) -> after.apply(apply(a, b, c));
        }
    }

    static int expensiveCalls = 0;

    static String expensiveDefault() {
        expensiveCalls++;
        return "default";
    }

    static void section8_functional() {
        section("8. Functional: lambdas, method refs, composition, laziness");

        // THE FOUR KINDS OF METHOD REFERENCE
        Function<String, Integer> staticRef = Integer::parseInt;          // 1. static method
        String greeting = "Hello, ";
        Function<String, String> boundRef = greeting::concat;             // 2. method of a SPECIFIC object
        Function<String, String> unboundRef = String::toUpperCase;        // 3. method of an ARBITRARY object (the argument)
        Supplier<List<String>> ctorRef = ArrayList::new;                  // 4. constructor
        check("static method ref", staticRef.apply("42"), 42);
        check("bound instance ref", boundRef.apply("Ann"), "Hello, Ann");
        check("unbound instance ref", unboundRef.apply("abc"), "ABC");
        check("constructor ref", ctorRef.get().isEmpty(), true);

        // COMPOSITION: build behavior from small pieces
        Function<Double, Double> addTax = p -> p * 1.10;
        Function<Double, Double> applyCoupon = p -> p - 5;
        check("andThen: coupon then tax", Math.round(applyCoupon.andThen(addTax).apply(100.0) * 100) / 100.0, 104.5);
        check("compose: tax then coupon", Math.round(applyCoupon.compose(addTax).apply(100.0) * 100) / 100.0, 105.0);

        Predicate<String> isLong = s -> s.length() > 4;
        Predicate<String> startsWithA = s -> s.startsWith("a");
        List<String> words = List.of("apple", "ant", "banana", "avocado", "fig");
        check("Predicate.and", words.stream().filter(isLong.and(startsWithA)).toList(), List.of("apple", "avocado"));
        check("Predicate.negate / or", words.stream().filter(isLong.negate().or(s -> s.equals("banana"))).toList(),
                List.of("ant", "banana", "fig"));

        // Custom functional interface + default method
        TriFunction<Integer, Integer, Integer, Integer> volume = (l, w, h) -> l * w * h;
        check("custom @FunctionalInterface", volume.andThen(v -> v + " cm3").apply(2, 3, 4), "24 cm3");

        // CURRYING: a function that returns a function (partial application)
        Function<Integer, Function<Integer, Integer>> multiplier = a -> b -> a * b;
        Function<Integer, Integer> triple = multiplier.apply(3);
        check("currying / partial application", triple.apply(7), 21);

        // CLOSURES capture EFFECTIVELY FINAL variables (the value, not the variable)
        int base = 10;
        IntUnaryOperator addBase = x -> x + base;
        // base++;   // <- COMPILE ERROR: captured variables must be effectively final
        check("closure captures base", addBase.applyAsInt(5), 15);

        // LAZINESS: orElse evaluates its argument ALWAYS, orElseGet only when needed
        expensiveCalls = 0;
        Optional.of("present").orElse(expensiveDefault());
        check("orElse is eager", expensiveCalls, 1);
        Optional.of("present").orElseGet(CoreJavaShowcase::expensiveDefault);
        check("orElseGet is lazy", expensiveCalls, 1);

        // Primitive specializations avoid boxing: IntPredicate, ToIntFunction, IntBinaryOperator...
        IntBinaryOperator gcd = new IntBinaryOperator() {
            @Override public int applyAsInt(int a, int b) { return b == 0 ? a : applyAsInt(b, a % b); }
        };
        check("IntBinaryOperator (recursive anon)", gcd.applyAsInt(48, 18), 6);

        // Grouping with collectors + method references
        Map<Boolean, List<String>> partitioned = words.stream().collect(Collectors.partitioningBy(isLong));
        check("partitioningBy with a Predicate", partitioned.get(true), List.of("apple", "banana", "avocado"));
    }

    // =========================================================================
    // 9. LANGUAGE GOTCHAS EVERY JAVA DEVELOPER SHOULD KNOW
    // =========================================================================

    static String overloaded(int... values) { return "varargs"; }
    static String overloaded(int a, int b) { return "exact"; }

    static int sum(int... values) {          // varargs: arrives as an int[]
        int s = 0;
        for (int v : values) s += v;
        return s;
    }

    static void reassign(List<String> list) {
        list = new ArrayList<>(List.of("new"));   // only changes the LOCAL copy of the reference
    }

    static void mutate(List<String> list) {
        list.add("added");                         // changes the SHARED object
    }

    /** An IMMUTABLE class: final class, final fields, defensive copies, no setters. */
    static final class LoanRecord {
        private final String memberName;
        private final List<String> itemIds;
        private final Date dueDate;                // Date is MUTABLE, so needs defensive copies

        LoanRecord(String memberName, List<String> itemIds, Date dueDate) {
            this.memberName = memberName;
            this.itemIds = List.copyOf(itemIds);               // copy IN
            this.dueDate = new Date(dueDate.getTime());        // copy IN
        }

        List<String> itemIds() { return itemIds; }             // already immutable
        Date dueDate() { return new Date(dueDate.getTime()); } // copy OUT
    }

    @SuppressWarnings({"StringEquality", "NumberEquality"})
    static void section9_languageGotchas() {
        section("9. Language gotchas: boxing, strings, varargs, pass-by-value");

        // AUTOBOXING + THE INTEGER CACHE: Integer.valueOf caches -128..127,
        // so == "works" for small values and fails for larger ones. ALWAYS use equals.
        Integer a = 127, b = 127, c = 128, d = 128;
        check("Integer 127 == 127 (cached)", a == b, true);
        check("Integer 128 == 128 (NOT cached)", c == d, false);
        check("use equals for wrappers", c.equals(d), true);

        Integer missing = null;
        check("unboxing null throws NPE", throwsName(() -> { int x = missing; }), "NullPointerException");

        // STRING POOL: literals are interned, `new String` creates a new object
        String lit = "library";
        String created = new String("library");
        check("literal vs new String with ==", lit == created, false);
        check("intern() returns the pooled instance", lit == created.intern(), true);
        check("compile-time constants are folded", lit == "lib" + "rary", true);

        // Strings are IMMUTABLE: "modifying" methods return NEW strings
        String s = "dune";
        s.toUpperCase();                                  // result discarded!
        check("String is immutable", s, "dune");

        // StringBuilder for building strings in loops (+ in a loop creates garbage)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) sb.append(i).append(',');
        sb.setLength(sb.length() - 1);
        check("StringBuilder", sb.reverse().toString(), "2,1,0");

        // VARARGS + overload resolution: exact matches beat varargs
        check("varargs sum", sum(1, 2, 3), 6);
        check("varargs with zero args", sum(), 0);
        check("exact overload beats varargs", overloaded(1, 2), "exact");

        // JAVA IS ALWAYS PASS-BY-VALUE. For objects, the value passed IS a reference.
        List<String> list = new ArrayList<>(List.of("orig"));
        reassign(list);
        check("reassigning a parameter: no effect", list, List.of("orig"));
        mutate(list);
        check("mutating through a parameter: visible", list, List.of("orig", "added"));

        // INTEGER OVERFLOW wraps silently. The Math.*Exact methods detect it.
        check("silent overflow", Integer.MAX_VALUE + 1, Integer.MIN_VALUE);
        check("Math.addExact detects overflow", throwsName(() -> Math.addExact(Integer.MAX_VALUE, 1)), "ArithmeticException");

        // Floating point is binary: 0.1 + 0.2 != 0.3. Use BigDecimal for money.
        check("double rounding error", 0.1 + 0.2 == 0.3, false);
        check("BigDecimal is exact", new BigDecimal("0.1").add(new BigDecimal("0.2")), new BigDecimal("0.3"));

        // IMMUTABILITY with defensive copies
        List<String> ids = new ArrayList<>(List.of("B1"));
        Date due = new Date(0);
        LoanRecord loan = new LoanRecord("Ann", ids, due);
        ids.add("B2");                  // caller mutates its own list...
        due.setTime(999_999);           // ...and its own Date
        loan.dueDate().setTime(5);      // and tries to mutate what the getter returned
        check("defensive copy (list)", loan.itemIds(), List.of("B1"));
        check("defensive copy (date)", loan.dueDate().getTime(), 0L);
    }

    // =========================================================================
    // 10. ANNOTATIONS AND REFLECTION
    // =========================================================================
    //
    // ANNOTATIONS are metadata. @Retention decides how long they live:
    //   SOURCE  - discarded by the compiler (@Override, @SuppressWarnings)
    //   CLASS   - kept in the .class file but invisible at runtime (default)
    //   RUNTIME - readable via reflection. Frameworks like Spring, JPA and
    //             Jakarta Bean Validation work this way.
    // @Target says WHERE an annotation may appear (FIELD, METHOD, TYPE...).
    //
    // REFLECTION inspects and uses classes at runtime: fields, methods,
    // constructors, annotations, generic signatures. It's powerful but slower
    // and bypasses compile-time checks, so it mostly belongs in frameworks.
    // =========================================================================

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface NotBlank {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @interface InRange {
        int min() default 0;          // annotation elements look like methods
        int max();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Audited {
        String value();
    }

    static class MemberForm {
        @NotBlank private String name;
        @InRange(min = 13, max = 120) private int age;
        @InRange(max = 10) private int requestedLoans;
        private Map<String, List<Integer>> history = new HashMap<>();   // generic field, inspected below

        MemberForm(String name, int age, int requestedLoans) {
            this.name = name;
            this.age = age;
            this.requestedLoans = requestedLoans;
        }

        @Audited("member-renewal")
        public String renew(int years) {
            return name + " renewed for " + years + " year(s)";
        }
    }

    /** A mini "Bean Validation" engine built on reflection. */
    static List<String> validate(Object target) throws IllegalAccessException {
        List<String> errors = new ArrayList<>();
        for (Field field : target.getClass().getDeclaredFields()) {
            field.setAccessible(true);                     // allow reading private fields
            Object value = field.get(target);

            if (field.isAnnotationPresent(NotBlank.class)
                    && (value == null || value.toString().isBlank())) {
                errors.add(field.getName() + " must not be blank");
            }
            InRange range = field.getAnnotation(InRange.class);
            if (range != null) {
                int n = (Integer) value;                   // an int field reads back as an Integer
                if (n < range.min() || n > range.max()) {
                    errors.add(field.getName() + " must be in [" + range.min() + ", " + range.max() + "]");
                }
            }
        }
        errors.sort(Comparator.naturalOrder());            // field order isn't guaranteed by the JVM
        return errors;
    }

    static void section10_annotationsAndReflection() throws Exception {
        section("10. Annotations + reflection");

        check("valid form", validate(new MemberForm("Ann", 30, 2)), List.of());
        check("invalid form", validate(new MemberForm(" ", 9, 50)), List.of(
                "age must be in [13, 120]",
                "name must not be blank",
                "requestedLoans must be in [0, 10]"));

        // Find annotated methods and invoke them reflectively
        MemberForm form = new MemberForm("Ann", 30, 2);
        Method renew = MemberForm.class.getMethod("renew", int.class);
        check("read method annotation", renew.getAnnotation(Audited.class).value(), "member-renewal");
        check("invoke via reflection", renew.invoke(form, 2), "Ann renewed for 2 year(s)");

        // GENERIC SIGNATURES SURVIVE ERASURE in class metadata. Objects don't know
        // their type arguments at runtime, but field/method DECLARATIONS do. This
        // is how JSON libraries (Jackson, Gson) deserialize Map<String, List<Integer>>.
        Field history = MemberForm.class.getDeclaredField("history");
        ParameterizedType type = (ParameterizedType) history.getGenericType();
        check("generic type args via reflection",
                Arrays.stream(type.getActualTypeArguments()).map(t -> t.getTypeName()).toList(),
                List.of("java.lang.String", "java.util.List<java.lang.Integer>"));
    }

    // =========================================================================
    // Test helpers
    // =========================================================================
    private static int passed = 0;

    static <T> void check(String label, T actual, T expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(label + "\n   expected: " + expected + "\n   actual:   " + actual);
        }
        passed++;
        System.out.printf("   ok  %-44s %s%n", label, actual);
    }

    static String throwsName(Runnable action) {
        try {
            action.run();
            return "no exception";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    static void section(String title) {
        System.out.printf("%n== %s ==%n", title);
    }
}