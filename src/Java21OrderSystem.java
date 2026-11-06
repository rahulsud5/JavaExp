import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * =============================================================================
 *  JAVA 21 SHOWCASE: A REALISTIC ONLINE-STORE ORDER SYSTEM
 * =============================================================================
 *
 *  This single file models a small but realistic order pipeline:
 *
 *     catalog -> customer places Order -> discounts applied -> shipping priced
 *       -> payment authorized (concurrently, on virtual threads)
 *       -> order moves through a validated status lifecycle -> receipt printed
 *
 *  Java 21 is a Long-Term-Support (LTS) release. It finalized several features
 *  that had been in preview for a few releases. Here is every major feature
 *  used below, with the Java version in which it became FINAL (non-preview):
 *
 *   FEATURE                                   FINAL IN   WHERE TO LOOK
 *   ----------------------------------------  ---------  ------------------------
 *   Records                                   Java 16    Money, Product, Order...
 *   Sealed classes & interfaces               Java 17    PaymentMethod, Discount,
 *                                                         OrderStatus, Result
 *   Pattern matching for instanceof           Java 16    greet()
 *   Switch expressions (arrow, yield)         Java 14    loyaltyPoints()
 *   Text blocks ("""...""")                   Java 15    printReceipt()
 *   Pattern matching for switch               Java 21 *  authorize(), describe()
 *   Record patterns (deconstruction)          Java 21 *  authorize(), isAllowed()
 *   Virtual threads                           Java 21 *  authorizeBatch()
 *   Sequenced collections                     Java 21 *  OrderTracker, demo
 *   Small new APIs: Math.clamp,               Java 21 *  newApisDemo()
 *     StringBuilder.repeat,
 *     String.splitWithDelimiters, ...
 *   (* = new in Java 21)
 *
 *  Java 21 also shipped PREVIEW features that need --enable-preview. They are
 *  intentionally NOT used here so the file compiles with plain `javac`; see the
 *  note at the very bottom of the file.
 *
 *  HOW TO RUN (JDK 21 or newer):
 *      java Java21OrderSystem.java                 (single-file source launch)
 *  or
 *      javac Java21OrderSystem.java && java Java21OrderSystem
 *
 *  The program checks its own results with check(...) and prints
 *  "All N checks passed." at the end.
 * =============================================================================
 */
public class Java21OrderSystem {

    // A fixed "today" keeps the program deterministic: card-expiry checks give
    // the same answer every time it runs.
    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);
    static final LocalDateTime NOW = TODAY.atTime(9, 0);

    // =========================================================================
    // PART 1 - RECORDS: immutable value types
    // =========================================================================
    //
    // A record is a class whose purpose is to CARRY DATA. From one line such as
    //     record Point(int x, int y) {}
    // the compiler generates:
    //   - private final fields x and y
    //   - a canonical constructor Point(int x, int y)
    //   - accessors x() and y()   (note: NOT getX()/getY())
    //   - equals(), hashCode() and toString() based on ALL components
    //
    // Rules worth knowing:
    //   - A record is implicitly final, and it cannot extend another class (it
    //     already extends java.lang.Record). It CAN implement interfaces, which
    //     is how records join sealed hierarchies below.
    //   - Instance fields can't be added; static fields and methods can.
    //   - Any instance method can be added, and generated ones can be overridden.
    //   - A record nested in a class is implicitly static.
    // =========================================================================

    /**
     * Money: a classic value object. Two Money values with the same amount and
     * currency ARE equal: value semantics come for free from the record.
     */
    record Money(BigDecimal amount, String currency) implements Comparable<Money> {

        // Static fields are allowed in records (instance fields are not).
        static final Money ZERO = usd("0.00");

        /*
         * COMPACT CANONICAL CONSTRUCTOR
         * -----------------------------
         * Note there is no parameter list. The parameters (amount, currency)
         * are implicitly available, and the fields are assigned AUTOMATICALLY
         * at the end of this block. That makes it the natural place for:
         *   1. validation (throw if invalid)
         *   2. normalization (reassign the PARAMETER, not this.amount)
         */
        Money {
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            if (amount.signum() < 0) {
                throw new IllegalArgumentException("Money cannot be negative: " + amount);
            }
            // Normalize the scale so that equals() works as expected:
            // BigDecimal("5.0").equals(BigDecimal("5.00")) is FALSE, because the
            // scale differs. Forcing scale 2 makes equal amounts compare equal.
            amount = amount.setScale(2, RoundingMode.HALF_EVEN);
        }

        // Static factory methods are a common way to give records friendly
        // constructors.
        static Money usd(String value) {
            return new Money(new BigDecimal(value), "USD");
        }

        Money plus(Money other) {
            requireSameCurrency(other);
            return new Money(amount.add(other.amount), currency);
        }

        /** Subtracts but never goes below zero (a discount can't make an order negative). */
        Money minusFloorZero(Money other) {
            requireSameCurrency(other);
            return new Money(amount.subtract(other.amount).max(BigDecimal.ZERO), currency);
        }

        Money times(int factor) {
            return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
        }

        Money percent(int pct) {
            return new Money(amount.multiply(BigDecimal.valueOf(pct))
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN), currency);
        }

        boolean isGreaterThan(Money other) {
            return compareTo(other) > 0;
        }

        @Override
        public int compareTo(Money other) {
            requireSameCurrency(other);
            return amount.compareTo(other.amount);
        }

        private void requireSameCurrency(Money other) {
            if (!currency.equals(other.currency)) {
                throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
            }
        }

        // Overriding the generated toString() for a friendlier format: $1,234.50
        @Override
        public String toString() {
            return "USD".equals(currency)
                    ? String.format(Locale.US, "$%,.2f", amount)
                    : String.format(Locale.US, "%,.2f %s", amount, currency);
        }
    }

    enum Category { ELECTRONICS, BOOKS, GROCERY }

    enum Tier { STANDARD, GOLD, PLATINUM }

    record Product(String sku, String name, Money price, Category category) {
        Product {
            if (sku == null || sku.isBlank()) throw new IllegalArgumentException("SKU is required");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(category, "category");
        }
    }

    record OrderLine(Product product, int quantity) {
        OrderLine {
            Objects.requireNonNull(product, "product");
            if (quantity < 1) throw new IllegalArgumentException("Quantity must be >= 1, was " + quantity);
        }

        Money subtotal() {
            return product.price().times(quantity);
        }
    }

    record Customer(String id, String name, Tier tier) {}

    // =========================================================================
    // PART 2 - SEALED INTERFACES + RECORDS = ALGEBRAIC DATA TYPES
    // =========================================================================
    //
    // A SEALED type says exactly which classes may implement or extend it:
    //
    //     sealed interface PaymentMethod permits CreditCard, PayPal, ... {}
    //
    // Why it matters:
    //   1. Domain modeling: "a payment is one of these four things, nothing
    //      else" is now a rule the COMPILER enforces, not just documentation.
    //   2. Exhaustive switch: since the compiler knows every subtype, a switch
    //      that covers all of them needs NO `default` branch. If someone adds
    //      a fifth payment type later, every switch that forgets it becomes a
    //      COMPILE ERROR. Without sealing, you'd get a runtime bug instead.
    //
    // Every permitted subtype must declare ONE of:
    //   final      - can't be extended further (records are implicitly final)
    //   sealed     - extended only by its own permitted list
    //   non-sealed - opened back up: anyone may extend it
    //
    // Records + sealed interfaces together are what functional languages call
    // "sum types" or "algebraic data types".
    // =========================================================================

    sealed interface PaymentMethod permits CreditCard, PayPal, BankTransfer, GiftCard {}

    record CreditCard(String number, YearMonth expiry, String holder) implements PaymentMethod {
        CreditCard {
            number = number.replaceAll("\\s", "");            // normalize "4111 1111 ..." -> "41111111..."
            if (!number.matches("\\d{12,19}")) {
                throw new IllegalArgumentException("Invalid card number");
            }
        }

        String last4() {
            return number.substring(number.length() - 4);
        }

        // SECURITY: the generated toString() would print the full card number into
        // logs. Overriding it to mask the number is a good real-world habit.
        // (The number() accessor still returns the full value for the gateway.)
        @Override
        public String toString() {
            return "CreditCard[**** " + last4() + ", exp " + expiry + ", " + holder + "]";
        }
    }

    record PayPal(String email) implements PaymentMethod {
        PayPal {
            if (email == null || !email.contains("@")) throw new IllegalArgumentException("Invalid email");
        }
    }

    record BankTransfer(String iban, String bic) implements PaymentMethod {}

    record GiftCard(String code, Money balance) implements PaymentMethod {}

    // -------------------------------------------------------------------------
    // A SEALED CLASS HIERARCHY (abstract class instead of interface)
    // -------------------------------------------------------------------------
    // Use a sealed abstract class when subtypes share STATE or code (here, the
    // promo `code` field). This hierarchy shows all three subclass modifiers:
    //
    //                 Discount  (abstract sealed)
    //       ┌─────────────┼──────────────┬─────────────────────┐
    //  PercentageOff  FixedAmountOff  BuyXGetYFree    PartnerDiscount
    //    (final)        (final)         (final)       (NON-SEALED, abstract)
    //                                                         │
    //                                               UniversityDiscount
    //                                       (any partner may add more subclasses)
    //
    // Making Discount ABSTRACT matters for exhaustiveness: no plain Discount
    // object can exist, so covering the four permitted subtypes covers all.
    // -------------------------------------------------------------------------

    abstract static sealed class Discount
            permits PercentageOff, FixedAmountOff, BuyXGetYFree, PartnerDiscount {

        private final String code;

        Discount(String code) {
            this.code = code;
        }

        String code() {
            return code;
        }

        /** How much this discount takes off the given order lines. */
        abstract Money amountOff(List<OrderLine> lines);

        static Money subtotalOf(List<OrderLine> lines) {
            return lines.stream().map(OrderLine::subtotal).reduce(Money.ZERO, Money::plus);
        }
    }

    static final class PercentageOff extends Discount {
        private final int percent;

        PercentageOff(String code, int percent) {
            super(code);
            if (percent < 1 || percent > 90) throw new IllegalArgumentException("Percent out of range");
            this.percent = percent;
        }

        int percent() { return percent; }

        @Override
        Money amountOff(List<OrderLine> lines) {
            return subtotalOf(lines).percent(percent);
        }
    }

    static final class FixedAmountOff extends Discount {
        private final Money amount;

        FixedAmountOff(String code, Money amount) {
            super(code);
            this.amount = amount;
        }

        Money amount() { return amount; }

        @Override
        Money amountOff(List<OrderLine> lines) {
            return amount;
        }
    }

    /** "Buy 2, get 1 free" style deal for one SKU. */
    static final class BuyXGetYFree extends Discount {
        private final String sku;
        private final int buy;
        private final int free;

        BuyXGetYFree(String code, String sku, int buy, int free) {
            super(code);
            this.sku = sku;
            this.buy = buy;
            this.free = free;
        }

        String sku() { return sku; }
        int buy() { return buy; }
        int free() { return free; }

        @Override
        Money amountOff(List<OrderLine> lines) {
            return lines.stream()
                    .filter(l -> l.product().sku().equals(sku))
                    .map(l -> {
                        int freeUnits = (l.quantity() / (buy + free)) * free;
                        return l.product().price().times(freeUnits);
                    })
                    .reduce(Money.ZERO, Money::plus);
        }
    }

    /**
     * NON-SEALED: this branch of the hierarchy is deliberately OPEN. Partner
     * companies can add their own deals by extending PartnerDiscount, while the
     * rest of Discount stays closed.
     */
    abstract static non-sealed class PartnerDiscount extends Discount {
        PartnerDiscount(String code) {
            super(code);
        }

        abstract String partner();
    }

    /** A partner deal: 15% off BOOKS only. Extends the non-sealed class freely. */
    static class UniversityDiscount extends PartnerDiscount {
        UniversityDiscount(String code) {
            super(code);
        }

        @Override
        String partner() { return "State University"; }

        @Override
        Money amountOff(List<OrderLine> lines) {
            List<OrderLine> books = lines.stream()
                    .filter(l -> l.product().category() == Category.BOOKS)
                    .toList();
            return subtotalOf(books).percent(15);
        }
    }

    // -------------------------------------------------------------------------
    // A sealed interface whose implementations mix an ENUM and RECORDS.
    // Enums are implicitly final (or sealed), so they may be permitted subtypes.
    // -------------------------------------------------------------------------
    sealed interface ShippingOption permits StandardShipping, ExpressShipping, StorePickup {}

    enum StandardShipping implements ShippingOption { ECONOMY, REGULAR }

    record ExpressShipping(int days) implements ShippingOption {
        ExpressShipping {
            if (days < 1 || days > 3) throw new IllegalArgumentException("Express is 1-3 days");
        }
    }

    record StorePickup(String storeId) implements ShippingOption {}

    // -------------------------------------------------------------------------
    // A GENERIC sealed type: Result<T> is either Success<T> or Failure<T>.
    // It's a typed alternative to throwing exceptions for EXPECTED failures
    // (declined payments, illegal state changes...).
    //
    // NOTE: there is no `permits` clause here. When every subtype is declared
    // in the SAME source file, the compiler infers the permitted list.
    // -------------------------------------------------------------------------
    sealed interface Result<T> {

        record Success<T>(T value) implements Result<T> {}

        record Failure<T>(String error) implements Result<T> {}

        /*
         * A default method that switches on `this`. Because Result is sealed,
         * the two cases are exhaustive: no default branch needed.
         * Record patterns pull the component out directly:
         *     case Success<T>(var value)   instead of   ((Success<T>) this).value()
         */
        default <U> Result<U> map(Function<? super T, ? extends U> fn) {
            return switch (this) {
                case Success<T>(var value) -> new Success<>(fn.apply(value));
                case Failure<T>(var error) -> new Failure<>(error);
            };
        }

        default boolean isSuccess() {
            return this instanceof Success<T>;
        }
    }

    // -------------------------------------------------------------------------
    // ORDER STATUS LIFECYCLE: a sealed hierarchy with a sealed SUB-interface.
    //
    //                  OrderStatus (sealed)
    //       ┌──────────┬────────┼───────────────┐
    //    Pending      Paid    Shipped      Terminal (sealed sub-interface)
    //                                     ┌──────┴──────┐
    //                                 Delivered     Cancelled
    //
    // `Terminal` lets code ask "is this a final state?" with a single pattern
    // (case Terminal t) instead of listing Delivered and Cancelled each time.
    // -------------------------------------------------------------------------
    sealed interface OrderStatus permits Pending, Paid, Shipped, Terminal {}

    record Pending() implements OrderStatus {}

    record Paid(String authCode) implements OrderStatus {}

    record Shipped(String carrier, String trackingNumber) implements OrderStatus {}

    sealed interface Terminal extends OrderStatus permits Delivered, Cancelled {}

    record Delivered(LocalDate on) implements Terminal {}

    record Cancelled(String reason) implements Terminal {}

    // -------------------------------------------------------------------------
    // The aggregate: an Order. It uses every type above.
    // -------------------------------------------------------------------------
    record Order(String id,
                 Customer customer,
                 List<OrderLine> lines,
                 PaymentMethod payment,
                 ShippingOption shipping,
                 List<Discount> discounts) {

        /*
         * DEFENSIVE COPIES. A record's fields are final, but a final reference
         * to a MUTABLE ArrayList is not immutable: the caller could still
         * change the list afterwards. List.copyOf makes an unmodifiable copy
         * (and rejects null elements), so the Order is truly immutable.
         */
        Order {
            Objects.requireNonNull(id, "id");
            lines = List.copyOf(lines);
            discounts = List.copyOf(discounts);
            if (lines.isEmpty()) throw new IllegalArgumentException("Order " + id + " has no lines");
        }

        Money subtotal() {
            return Discount.subtotalOf(lines);
        }

        Money discountTotal() {
            Money total = discounts.stream().map(d -> d.amountOff(lines)).reduce(Money.ZERO, Money::plus);
            // A discount can never exceed the subtotal
            return total.isGreaterThan(subtotal()) ? subtotal() : total;
        }

        Money afterDiscounts() {
            return subtotal().minusFloorZero(discountTotal());
        }

        Money shippingCost() {
            return Java21OrderSystem.shippingCost(shipping, afterDiscounts());
        }

        Money total() {
            return afterDiscounts().plus(shippingCost());
        }
    }

    // =========================================================================
    // PART 3 - PATTERN MATCHING FOR SWITCH + RECORD PATTERNS  (Java 21)
    // =========================================================================
    //
    // Before Java 21, switch only worked on primitives, strings and enums. Now
    // it works on ANY type, and each `case` can be a PATTERN:
    //
    //   case CreditCard c                 TYPE pattern: matches, then binds c
    //   case CreditCard(var n, var e, var h)
    //                                     RECORD pattern: matches and
    //                                     DECONSTRUCTS into components
    //   case GiftCard(var c, var b) when b.isGreaterThan(x)
    //                                     GUARDED pattern: extra condition
    //   case StandardShipping.ECONOMY     QUALIFIED ENUM CONSTANT used as a case
    //                                     label when switching on the sealed
    //                                     interface (new in 21)
    //   case null                         explicit null handling (new in 21).
    //                                     Without it, a null selector throws
    //                                     NullPointerException.
    //
    // ORDER MATTERS: cases are tried top to bottom. A guarded case must come
    // BEFORE the unguarded case for the same type. The compiler reports an
    // error if a case can never match ("dominated" by an earlier case).
    // =========================================================================

    static final Money FREE_SHIPPING_THRESHOLD = Money.usd("50.00");
    static final Money BANK_TRANSFER_LIMIT = Money.usd("10000.00");

    /**
     * Shipping price. Shows enum constants and record patterns mixed in ONE
     * switch over a sealed interface. Listing both enum constants (ECONOMY,
     * REGULAR) counts as covering the whole enum, so the switch is exhaustive.
     */
    static Money shippingCost(ShippingOption option, Money merchandise) {
        return switch (option) {
            case StandardShipping.ECONOMY ->
                    merchandise.compareTo(FREE_SHIPPING_THRESHOLD) >= 0 ? Money.ZERO : Money.usd("4.99");
            case StandardShipping.REGULAR -> Money.usd("7.99");
            case ExpressShipping(int days) when days == 1 -> Money.usd("24.99");   // next-day premium
            case ExpressShipping(int days) -> Money.usd("14.99");
            case StorePickup(String storeId) -> Money.ZERO;
        };
    }

    /**
     * Payment authorization: the heart of the pattern-matching demo.
     *
     * Compare with the pre-Java-21 version:
     *
     *     if (method instanceof CreditCard) {
     *         CreditCard cc = (CreditCard) method;          // manual cast
     *         if (cc.expiry().isBefore(...)) { ... }
     *     } else if (method instanceof PayPal) { ... }
     *     ... and a final `else throw` for "impossible" cases
     *
     * The switch below has no casts and no `else throw`. The compiler PROVES
     * that all four payment types are handled.
     */
    static Result<String> authorize(PaymentMethod method, Money amount, LocalDate today) {
        return switch (method) {
            // Record pattern + guard: deconstruct, then check the expiry
            case CreditCard(var number, var expiry, var holder)
                    when expiry.isBefore(YearMonth.from(today)) ->
                    new Result.Failure<>("Card expired " + expiry);

            // Type pattern: any OTHER credit card (valid expiry)
            case CreditCard card -> new Result.Success<>("AUTH-CC-" + card.last4());

            // Explicit component types work too (String email instead of var)
            case PayPal(String email) -> new Result.Success<>("AUTH-PP-" + email);

            case BankTransfer(var iban, var bic) when amount.isGreaterThan(BANK_TRANSFER_LIMIT) ->
                    new Result.Failure<>("Bank transfers over " + BANK_TRANSFER_LIMIT + " require manual review");
            case BankTransfer(var iban, var bic) -> new Result.Success<>("AUTH-BT-" + iban.substring(0, 4));

            case GiftCard(var code, var balance) when amount.isGreaterThan(balance) ->
                    new Result.Failure<>("Gift card balance " + balance + " is less than " + amount);
            case GiftCard(var code, var balance) -> new Result.Success<>("AUTH-GC-" + code);
        };
    }

    /** Human-readable status. Shows `case null`, nested deconstruction and guards. */
    static String describe(OrderStatus status) {
        return switch (status) {
            case null -> "Unknown status";                          // Java 21: null is a legal case
            case Pending p -> "Awaiting payment";
            case Paid(String authCode) -> "Paid (auth " + authCode + ")";
            case Shipped(var carrier, var tracking) when carrier.equals("UPS") ->
                    "Shipped via UPS, track at ups.com: " + tracking;
            case Shipped(var carrier, var tracking) -> "Shipped via " + carrier + ": " + tracking;
            case Delivered(LocalDate on) -> "Delivered on " + on;
            case Cancelled(String reason) -> "Cancelled: " + reason;
        };
    }

    /** Exhaustive switch over a sealed ABSTRACT CLASS hierarchy. */
    static String describeDiscount(Discount discount) {
        return switch (discount) {
            case PercentageOff p -> p.percent() + "% off";
            case FixedAmountOff f -> f.amount() + " off";
            case BuyXGetYFree b -> "buy " + b.buy() + " get " + b.free() + " free (" + b.sku() + ")";
            case PartnerDiscount p -> "partner deal: " + p.partner();   // covers ALL partner subclasses
        };
    }

    // -------------------------------------------------------------------------
    // NESTED RECORD PATTERNS for a state machine.
    //
    // A tiny record pairs (from, to) so we can switch on BOTH values at once.
    // Each case deconstructs the Transition AND type-tests its two components
    // in one line. That makes the transition table read like a specification.
    // -------------------------------------------------------------------------
    record Transition(OrderStatus from, OrderStatus to) {}

    static boolean isAllowed(OrderStatus from, OrderStatus to) {
        return switch (new Transition(from, to)) {
            case Transition(Pending p, Paid next) -> true;
            case Transition(Pending p, Cancelled next) -> true;
            case Transition(Paid p, Shipped next) -> true;
            case Transition(Paid p, Cancelled next) -> true;
            case Transition(Shipped s, Delivered next) -> true;
            case Transition(Terminal t, var next) -> false;           // nothing may follow a final state
            case Transition(var anyFrom, var anyTo) -> false;         // catch-all pattern (acts like default)
        };
    }

    // =========================================================================
    // PART 4 - SEQUENCED COLLECTIONS  (Java 21)
    // =========================================================================
    //
    // Before Java 21, collections with a defined order had NO common interface.
    // Getting the last element looked different for each type:
    //     list.get(list.size() - 1)
    //     deque.getLast()
    //     sortedSet.last()
    //     linkedHashSet: iterate over the whole set!
    //
    // Java 21 added three interfaces that ordered collections now implement:
    //   SequencedCollection: getFirst(), getLast(), addFirst(), addLast(),
    //                        removeFirst(), removeLast(), reversed()
    //   SequencedSet       : the same, for LinkedHashSet / TreeSet ...
    //   SequencedMap       : firstEntry(), lastEntry(), putFirst(), putLast(),
    //                        pollFirstEntry(), pollLastEntry(), reversed(),
    //                        sequencedKeySet(), ...
    // List, Deque, LinkedHashSet, TreeSet, LinkedHashMap and TreeMap all get them.
    // `reversed()` returns a VIEW (no copying).
    // =========================================================================

    /** Tracks an order's status history in insertion order (oldest -> newest). */
    static final class OrderTracker {
        private final String orderId;
        // Declared by its SequencedMap interface type, backed by LinkedHashMap
        private final SequencedMap<LocalDateTime, OrderStatus> history = new LinkedHashMap<>();

        OrderTracker(String orderId, LocalDateTime createdAt) {
            this.orderId = orderId;
            history.put(createdAt, new Pending());
        }

        OrderStatus current() {
            return history.lastEntry().getValue();    // was: iterate to the end
        }

        OrderStatus initial() {
            return history.firstEntry().getValue();
        }

        /** Returns a Result instead of throwing: an illegal transition is an expected outcome. */
        Result<OrderStatus> moveTo(OrderStatus next, LocalDateTime at) {
            OrderStatus current = current();
            if (!isAllowed(current, next)) {
                return new Result.Failure<>("Order " + orderId + ": illegal transition "
                        + current.getClass().getSimpleName() + " -> " + next.getClass().getSimpleName());
            }
            history.putLast(at, next);                 // SequencedMap.putLast
            return new Result.Success<>(next);
        }

        /** Newest first: reversed() is a cheap view, not a copy. */
        List<String> timelineNewestFirst() {
            return history.reversed().values().stream()
                    .map(s -> s.getClass().getSimpleName())
                    .toList();
        }
    }

    // =========================================================================
    // PART 5 - VIRTUAL THREADS  (Java 21)
    // =========================================================================
    //
    // A platform (classic) thread wraps an OS thread: about 1 MB of stack,
    // expensive to create, and a few thousand of them is a practical limit.
    // A VIRTUAL thread is managed by the JVM and costs a few hundred bytes, so
    // you can run MILLIONS of them. When a virtual thread blocks (sleep,
    // socket read, JDBC call...), the JVM unmounts it from its carrier OS
    // thread, which is then free to run other virtual threads.
    //
    // Result: you write plain, blocking, easy-to-debug code ("thread per
    // request") and get the scalability of async/reactive code.
    //
    // Guidelines:
    //   - Use them for I/O-bound work (HTTP calls, DB queries). They don't make
    //     CPU-bound work faster.
    //   - Don't POOL virtual threads; create one per task.
    //   - Prefer ReentrantLock over long `synchronized` blocks that do I/O:
    //     in Java 21 those "pin" the carrier thread. (JDK 24 later removed most
    //     pinning caused by synchronized.)
    // =========================================================================

    /** Simulates a call to a remote payment gateway that takes 100 ms. */
    static Result<String> authorizeViaGateway(Order order) throws InterruptedException {
        Thread.sleep(Duration.ofMillis(100));     // blocking is fine: only the virtual thread parks
        return authorize(order.payment(), order.total(), TODAY);
    }

    record BatchReport(int tasks, long approved, long declined, long elapsedMillis) {}

    static BatchReport authorizeBatch(List<Order> orders, int tasks) {
        long start = System.nanoTime();
        List<Future<Result<String>>> futures = new ArrayList<>(tasks);

        // One NEW virtual thread per task. ExecutorService is AutoCloseable
        // (since Java 19): close() at the end of try-with-resources WAITS for
        // every submitted task to finish.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                Order order = orders.get(i % orders.size());
                futures.add(executor.submit(() -> authorizeViaGateway(order)));
            }
        }

        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // Future.resultNow() (Java 19+) returns the result of a COMPLETED
        // future with no checked exceptions to handle.
        long approved = futures.stream().map(Future::resultNow).filter(Result::isSuccess).count();
        return new BatchReport(tasks, approved, tasks - approved, elapsed);
    }

    // =========================================================================
    // PART 6 - SWITCH EXPRESSIONS, instanceof PATTERNS, TEXT BLOCKS, var
    // =========================================================================

    static final int MAX_POINTS_PER_ORDER = 1_000;

    /**
     * SWITCH EXPRESSION (Java 14): the switch PRODUCES a value. Arrow cases
     * can't fall through, so no `break` is needed. A switch over an enum is
     * exhaustive when every constant is listed. A block body returns its value
     * with `yield`.
     */
    static int loyaltyPoints(Customer customer, Money total) {
        int multiplier = switch (customer.tier()) {
            case STANDARD -> 1;
            case GOLD -> 2;
            case PLATINUM -> {
                // A block is useful when a case needs several statements
                int base = 2;
                int vipBonus = 1;
                yield base + vipBonus;
            }
        };
        long raw = total.amount().longValue() / 10 * multiplier;  // 1 point per $10
        // Math.clamp (Java 21): bound a value to [min, max] in one call.
        // Replaces Math.max(min, Math.min(max, value)).
        return Math.clamp(raw, 0, MAX_POINTS_PER_ORDER);
    }

    /**
     * PATTERN MATCHING FOR instanceof (Java 16), plus a RECORD PATTERN inside
     * instanceof (Java 21). The binding variables can be used right away,
     * including later in the same && condition.
     */
    static String greet(Object visitor) {
        if (visitor instanceof Customer(var id, var name, var tier) && tier == Tier.PLATINUM) {
            return "Welcome back, " + name + "! Your VIP lounge is ready.";
        }
        if (visitor instanceof Customer c) {      // was: if (o instanceof Customer) { Customer c = (Customer) o; ...
            return "Hello, " + c.name() + "!";
        }
        return "Hello, guest!";
    }

    /**
     * TEXT BLOCKS (Java 15): multi-line strings with no \n or + concatenation.
     *   - Common leading indentation is stripped, based on the least-indented
     *     line, including the line with the closing """.
     *   - String.formatted(...) (Java 15) is the instance-method version of
     *     String.format.
     *   - A trailing backslash joins lines; \s keeps a trailing space.
     *
     * `var` (Java 10): local type inference. Use it when the type is obvious
     * from the right-hand side.
     */
    static String printReceipt(Order order) {
        // StringBuilder.repeat (Java 21): repeat a char sequence N times
        var rule = new StringBuilder().repeat("-", 46).toString();
        var thickRule = new StringBuilder().repeat("=", 46).toString();

        var lines = order.lines().stream()
                .map(l -> "%-28s x%-3d %12s".formatted(l.product().name(), l.quantity(), l.subtotal()))
                .collect(Collectors.joining("\n"));

        var discounts = order.discounts().isEmpty()
                ? "(no discounts)"
                : order.discounts().stream()
                .map(d -> "%-12s %s".formatted(d.code(), describeDiscount(d)))
                .collect(Collectors.joining("\n"));

        return """
                %s
                RECEIPT #%s
                Customer : %s (%s)
                %s
                %s
                %s
                %s
                Subtotal : %35s
                Discounts: %35s
                Shipping : %35s
                TOTAL    : %35s
                Points earned: %d
                %s""".formatted(
                thickRule,
                order.id(),
                order.customer().name(), order.customer().tier(),
                rule,
                lines,
                rule,
                discounts,
                order.subtotal(),
                "-" + order.discountTotal(),
                order.shippingCost(),
                order.total(),
                loyaltyPoints(order.customer(), order.total()),
                thickRule);
    }

    // =========================================================================
    //                                  MAIN
    // =========================================================================

    public static void main(String[] args) throws Exception {

        // ---------------------------------------------------------------- data
        var laptop = new Product("LAPTOP", "Laptop Pro 14", Money.usd("1299.00"), Category.ELECTRONICS);
        var headphones = new Product("HEADPH", "Noise-Cancel Headphones", Money.usd("199.99"), Category.ELECTRONICS);
        var book = new Product("BOOK-EJ", "Effective Java", Money.usd("45.00"), Category.BOOKS);
        var coffee = new Product("COFFEE", "Coffee Beans 1kg", Money.usd("18.50"), Category.GROCERY);

        var alice = new Customer("C1", "Alice", Tier.GOLD);
        var bob = new Customer("C2", "Bob", Tier.STANDARD);
        var carol = new Customer("C3", "Carol", Tier.PLATINUM);
        var dave = new Customer("C4", "Dave", Tier.STANDARD);
        var erin = new Customer("C5", "Erin", Tier.STANDARD);

        var orderA = new Order("A-1001", alice,
                List.of(new OrderLine(laptop, 1), new OrderLine(book, 2)),
                new CreditCard("4111 1111 1111 4242", YearMonth.of(2028, 9), "Alice Smith"),
                new ExpressShipping(1),
                List.of(new PercentageOff("GOLD10", 10)));

        var orderB = new Order("B-1002", bob,
                List.of(new OrderLine(coffee, 3)),
                new PayPal("bob@example.com"),
                StandardShipping.ECONOMY,
                List.of(new BuyXGetYFree("COFFEE3FOR2", "COFFEE", 2, 1)));

        var orderC = new Order("C-1003", carol,
                List.of(new OrderLine(headphones, 1)),
                new GiftCard("GC-777", Money.usd("50.00")),
                new StorePickup("NYC-5TH-AVE"),
                List.of(new FixedAmountOff("WELCOME20", Money.usd("20.00"))));

        var orderD = new Order("D-1004", dave,
                List.of(new OrderLine(book, 1)),
                new CreditCard("5555555555554444", YearMonth.of(2024, 1), "Dave Jones"),
                StandardShipping.REGULAR,
                List.of());

        var orderE = new Order("E-1005", erin,
                List.of(new OrderLine(laptop, 10), new OrderLine(book, 4)),
                new BankTransfer("DE89370400440532013000", "COBADEFFXXX"),
                StandardShipping.REGULAR,
                List.of(new UniversityDiscount("UNI15")));

        List<Order> orders = List.of(orderA, orderB, orderC, orderD, orderE);

        // ------------------------------------------------------ 1. records
        section("1. Records: value semantics, validation, defensive copies");

        check("equal values => equal records", Money.usd("5.0").equals(Money.usd("5.00")), true);
        check("records work as map keys",
                new HashSet<>(List.of(Money.usd("1"), Money.usd("1.00"), Money.usd("2"))).size(), 2);
        check("negative Money rejected",
                throwsException(() -> Money.usd("-1")), "IllegalArgumentException");
        check("quantity 0 rejected",
                throwsException(() -> new OrderLine(book, 0)), "IllegalArgumentException");
        check("bad card number rejected",
                throwsException(() -> new CreditCard("12ab", YearMonth.of(2030, 1), "X")), "IllegalArgumentException");
        check("toString masks card number", orderA.payment().toString(),
                "CreditCard[**** 4242, exp 2028-09, Alice Smith]");

        var mutableLines = new ArrayList<>(List.of(new OrderLine(book, 1)));
        var defensive = new Order("X-1", bob, mutableLines, new PayPal("b@x.io"), StandardShipping.REGULAR, List.of());
        mutableLines.add(new OrderLine(coffee, 5));                  // mutate the caller's list...
        check("defensive copy protects Order", defensive.lines().size(), 1);   // ...the order is unaffected
        check("Order.lines() is unmodifiable",
                throwsException(() -> defensive.lines().add(new OrderLine(book, 1))), "UnsupportedOperationException");

        // ---------------------------------------- 2. sealed types, pricing
        section("2. Sealed hierarchies + exhaustive switches: pricing");

        check("A subtotal", orderA.subtotal(), Money.usd("1389.00"));
        check("A discount (10%)", orderA.discountTotal(), Money.usd("138.90"));
        check("A shipping (express 1 day)", orderA.shippingCost(), Money.usd("24.99"));
        check("A total", orderA.total(), Money.usd("1275.09"));

        check("B discount (buy 2 get 1)", orderB.discountTotal(), Money.usd("18.50"));
        check("B economy shipping (< $50)", orderB.shippingCost(), Money.usd("4.99"));
        check("B total", orderB.total(), Money.usd("41.99"));

        check("C total (store pickup)", orderC.total(), Money.usd("179.99"));
        check("D total (regular shipping)", orderD.total(), Money.usd("52.99"));
        check("E partner discount (15% books)", orderE.discountTotal(), Money.usd("27.00"));
        check("E total", orderE.total(), Money.usd("13150.99"));

        check("describe discounts", orders.stream()
                        .flatMap(o -> o.discounts().stream())
                        .map(Java21OrderSystem::describeDiscount)
                        .toList(),
                List.of("10% off", "buy 2 get 1 free (COFFEE)", "$20.00 off", "partner deal: State University"));

        // ------------------------------- 3. pattern matching: authorization
        section("3. Pattern matching for switch + record patterns: payments");

        List<String> outcomes = orders.stream()
                .map(o -> {
                    Result<String> result = authorize(o.payment(), o.total(), TODAY);
                    // Record patterns on a GENERIC record: the type argument is
                    // INFERRED (Result.Success(var code), no <String> needed).
                    return o.id() + " " + switch (result) {
                        case Result.Success(var code) -> "APPROVED " + code;
                        case Result.Failure(var error) -> "DECLINED " + error;
                    };
                })
                .toList();
        outcomes.forEach(s -> System.out.println("      " + s));
        check("authorization outcomes", outcomes, List.of(
                "A-1001 APPROVED AUTH-CC-4242",
                "B-1002 APPROVED AUTH-PP-bob@example.com",
                "C-1003 DECLINED Gift card balance $50.00 is less than $179.99",
                "D-1004 DECLINED Card expired 2024-01",
                "E-1005 DECLINED Bank transfers over $10,000.00 require manual review"));

        // Result.map: transform a success, pass a failure through untouched
        Result<Integer> codeLength = authorize(orderA.payment(), orderA.total(), TODAY).map(String::length);
        check("Result.map on success", codeLength, new Result.Success<>(12));
        Result<Integer> failedLength = authorize(orderD.payment(), orderD.total(), TODAY).map(String::length);
        check("Result.map on failure", failedLength, new Result.Failure<>("Card expired 2024-01"));

        // ------------------------------- 4. state machine + sequenced map
        section("4. Order lifecycle: nested record patterns + SequencedMap");

        var tracker = new OrderTracker(orderA.id(), NOW);
        check("initial status", describe(tracker.current()), "Awaiting payment");
        check("Pending -> Paid", tracker.moveTo(new Paid("AUTH-CC-4242"), NOW.plusMinutes(1)).isSuccess(), true);

        var skipped = tracker.moveTo(new Delivered(TODAY), NOW.plusMinutes(2));
        check("Paid -> Delivered is illegal", skipped,
                new Result.Failure<>("Order A-1001: illegal transition Paid -> Delivered"));

        check("Paid -> Shipped", tracker.moveTo(new Shipped("UPS", "1Z999AA10123456784"), NOW.plusDays(1)).isSuccess(), true);
        check("shipped description", describe(tracker.current()),
                "Shipped via UPS, track at ups.com: 1Z999AA10123456784");
        check("Shipped -> Delivered", tracker.moveTo(new Delivered(TODAY.plusDays(3)), NOW.plusDays(3)).isSuccess(), true);
        check("nothing after a Terminal state",
                tracker.moveTo(new Cancelled("changed mind"), NOW.plusDays(4)).isSuccess(), false);

        check("SequencedMap.firstEntry", describe(tracker.initial()), "Awaiting payment");
        check("SequencedMap.lastEntry", describe(tracker.current()), "Delivered on 2026-11-08");
        check("reversed() timeline", tracker.timelineNewestFirst(),
                List.of("Delivered", "Shipped", "Paid", "Pending"));
        check("case null handled", describe(null), "Unknown status");

        // ------------------------------------ 5. more sequenced collections
        section("5. Sequenced collections: lists and sets");

        check("List.getFirst()", orders.getFirst().id(), "A-1001");
        check("List.getLast()", orders.getLast().id(), "E-1005");
        check("List.reversed()", orders.reversed().stream().map(Order::id).limit(2).toList(),
                List.of("E-1005", "D-1004"));

        // "Recently viewed" products: LinkedHashSet.addFirst MOVES an existing
        // element to the front. That's exactly what a recent-items list needs.
        var recentlyViewed = new LinkedHashSet<String>();
        for (var sku : List.of("LAPTOP", "BOOK-EJ", "COFFEE", "LAPTOP")) {
            recentlyViewed.addFirst(sku);
            if (recentlyViewed.size() > 3) recentlyViewed.removeLast();   // keep only the 3 most recent
        }
        check("recently viewed (most recent first)", List.copyOf(recentlyViewed),
                List.of("LAPTOP", "COFFEE", "BOOK-EJ"));

        // ----------------------- 6. local records + streams: sales report
        section("6. Local records for intermediate results");

        // A LOCAL RECORD is declared inside a method and visible only there.
        // It's ideal for naming intermediate results instead of using
        // Map.Entry or Object[] tuples.
        record CategoryRevenue(Category category, Money revenue) {}

        List<CategoryRevenue> report = orders.stream()
                .flatMap(o -> o.lines().stream())
                .collect(Collectors.groupingBy(
                        l -> l.product().category(),
                        () -> new EnumMap<>(Category.class),
                        Collectors.reducing(Money.ZERO, OrderLine::subtotal, Money::plus)))
                .entrySet().stream()
                .map(e -> new CategoryRevenue(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(CategoryRevenue::revenue).reversed())
                .toList();
        report.forEach(r -> System.out.printf("      %-12s %s%n", r.category(), r.revenue()));
        check("top category", report.getFirst(), new CategoryRevenue(Category.ELECTRONICS, Money.usd("14488.99")));
        check("last category", report.getLast(), new CategoryRevenue(Category.GROCERY, Money.usd("55.50")));

        // ------------------------------------------- 7. virtual threads
        section("7. Virtual threads: 10,000 concurrent gateway calls");

        // 10,000 calls x 100 ms each = 1,000 seconds if run one by one.
        // 10,000 PLATFORM threads would strain most machines. 10,000 VIRTUAL
        // threads is routine and finishes in well under a second.
        BatchReport batch = authorizeBatch(orders, 10_000);
        System.out.printf("      %,d authorizations in %d ms (%,d approved, %,d declined)%n",
                batch.tasks(), batch.elapsedMillis(), batch.approved(), batch.declined());
        check("approved (2 of every 5 orders)", batch.approved(), 4_000L);
        check("finished far faster than sequential", batch.elapsedMillis() < 10_000, true);

        // Thread.ofVirtual(): the builder API, for a single named virtual thread
        var receiptHolder = new AtomicReference<String>();
        Thread printer = Thread.ofVirtual()
                .name("receipt-printer")
                .start(() -> receiptHolder.set(printReceipt(orderA)));
        printer.join();
        check("thread is virtual", printer.isVirtual(), true);

        // -------------------------------- 8. switch expr, instanceof, text blocks
        section("8. Switch expressions, instanceof patterns, text blocks");

        check("GOLD points (x2)", loyaltyPoints(alice, orderA.total()), 254);
        check("PLATINUM points (x3, yield)", loyaltyPoints(carol, orderC.total()), 51);
        check("Math.clamp caps points", loyaltyPoints(erin, orderE.total()), 1_000);

        check("greet PLATINUM", greet(carol), "Welcome back, Carol! Your VIP lounge is ready.");
        check("greet GOLD", greet(alice), "Hello, Alice!");
        check("greet non-customer", greet("some string"), "Hello, guest!");

        String receipt = receiptHolder.get();
        System.out.println();
        System.out.println(receipt.indent(6));   // String.indent (Java 12) shifts every line right
        check("receipt total line", receipt.lines().anyMatch(l -> l.startsWith("TOTAL") && l.endsWith("$1,275.09")), true);
        check("receipt line count", receipt.lines().count(), 14L);

        // ------------------------------------------ 9. small new APIs
        section("9. Small but handy Java 21 API additions");

        check("StringBuilder.repeat", new StringBuilder("ab").repeat("-", 3).toString(), "ab---");
        check("Math.clamp", Math.clamp(150, 0, 100), 100);
        check("String.splitWithDelimiters",
                List.of("SAVE10+FREESHIP-BOOKS".splitWithDelimiters("[+-]", 0)),
                List.of("SAVE10", "+", "FREESHIP", "-", "BOOKS"));
        check("String.indexOf(ch, from, to)", "coffee".indexOf('e', 0, 4), -1);  // search only "coff"
        check("Character.isEmoji (U+2615 coffee)", Character.isEmoji(0x2615), true);

        System.out.printf("%nAll %d checks passed.%n", passed);
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
        System.out.printf("   ok  %-38s %s%n", label, actual);
    }

    static String throwsException(Runnable action) {
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

/*
 * =============================================================================
 *  NOTE: JAVA 21 PREVIEW FEATURES (not used above)
 * =============================================================================
 *  Java 21 also shipped these as PREVIEW (they need --enable-preview), so they
 *  were left out of this file to keep it compiling with plain javac 21:
 *
 *  - Unnamed patterns and variables:  case Shipped(var carrier, _) ->
 *      Lets you write `_` for components you don't use (in this file you'll
 *      see placeholder names like `iban`, `bic` instead). Final in Java 22.
 *
 *  - Unnamed classes and instance main methods:  void main() { ... }
 *      A beginner-friendly program with no class declaration. Finalized
 *      later (Java 25) as "compact source files and instance main methods".
 *
 *  - Structured concurrency (StructuredTaskScope): treats a group of
 *      concurrent subtasks as one unit. If one fails, the others are
 *      cancelled. A natural partner for virtual threads.
 *
 *  - Scoped values (ScopedValue): an immutable, cheaper alternative to
 *      ThreadLocal, designed for huge numbers of virtual threads.
 *
 *  - String templates (STR."Hello \{name}"): previewed in 21 and 22, then
 *      WITHDRAWN in Java 23 for a redesign. Don't rely on them.
 *
 *  Also in Java 21 but not visible in code: Generational ZGC
 *  (-XX:+UseZGC -XX:+ZGenerational), a low-pause garbage collector that now
 *  separates young and old objects for better throughput.
 * =============================================================================
 */