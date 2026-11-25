import java.io.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.management.*;
import java.lang.ref.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * =============================================================================
 *  JVM INTERNALS SHOWCASE
 *  Memory model, memory areas, garbage collection, class loading and more
 * =============================================================================
 *
 *   1. Java Memory Model   reordering caught live (a "litmus test"),
 *                          volatile, VarHandle acquire/release, JIT hoisting,
 *                          final-field guarantees, word tearing
 *   2. Fail-fast vs        modCount, ConcurrentModificationException, the
 *      fail-safe           "second-to-last element" quirk, snapshot and
 *                          weakly consistent iterators
 *   3. Memory areas        heap vs stack, frames, StackOverflowError, -Xss,
 *                          metaspace, direct (off-heap) memory, the kinds of
 *                          OutOfMemoryError
 *   4. GC fundamentals     reachability, GC roots, cycles, strong / soft /
 *                          weak / phantom references, ReferenceQueue,
 *                          WeakHashMap, Cleaner (replaces finalize), leaks
 *   5. GC algorithms and   generational hypothesis, mark-sweep-compact,
 *      collectors          copying, TLABs, barriers, safepoints. Serial,
 *                          Parallel, G1, ZGC, Shenandoah and Epsilon are all
 *                          started for real in child JVMs, plus live GC logs,
 *                          soft-reference clearing, and OOM
 *   6. Class loading       loader hierarchy, parent delegation, class identity
 *                          = name + loader, lazy initialization rules
 *   7. HashMap internals   hashing, buckets, collisions, treeification, resize
 *   8. Serialization and   transient, serialVersionUID, readObject validation,
 *      cloning             readResolve singletons, shallow vs deep copies
 *   9. JIT and tuning      tiered compilation, escape analysis, JVM flags and
 *                          diagnostic tools (cheat sheet)
 *
 *  RUN (JDK 21+, a full JDK, because it compiles a helper class):
 *      java JvmInternalsShowcase.java
 *  Takes about 10-20 s (it starts several child JVMs). Ends with
 *  "All N checks passed."
 *
 *  Some JVM behaviors are NON-DETERMINISTIC by nature (whether a race shows
 *  up, how the JIT compiles a loop). For those, the program PRINTS what it
 *  observed instead of asserting. Everything the Java spec GUARANTEES is
 *  asserted with check().
 * =============================================================================
 */
public class JvmInternalsShowcase {

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        Path labDir = compileGcLab();       // the helper class run in child JVMs

        section1_memoryModel(labDir);
        section2_failFastFailSafe();
        section3_memoryAreas();
        section4_gcFundamentals();
        section5_collectors(labDir);
        section6_classLoading(labDir);
        section7_hashMapInternals();
        section8_serializationAndCloning();
        section9_jitAndTuning();

        System.out.printf("%nAll %d checks passed in %.1f s.%n", passed, (System.nanoTime() - start) / 1e9);
    }

    // =========================================================================
    // 1. THE JAVA MEMORY MODEL (JMM, JSR-133, JLS chapter 17)
    // =========================================================================
    //
    // WHY A MEMORY MODEL? Your code is NOT executed in the order you wrote it.
    //   - The COMPILER (javac/JIT) reorders, caches values in registers, and
    //     hoists reads out of loops.
    //   - The CPU executes out of order and buffers writes in a STORE BUFFER
    //     before they reach the shared cache.
    //   - Each core has its own caches.
    // In a single thread you can never tell (the "as-if-serial" guarantee). But
    // ANOTHER thread can observe the reordering. The JMM defines exactly
    // which values a read is allowed to see.
    //
    // HAPPENS-BEFORE (HB) is the core rule: if A happens-before B, then A's
    // effects are visible to B, and B can't appear to run before A.
    // HB edges are created by:
    //   - program order inside one thread
    //   - unlock of a monitor        -> every later lock of the SAME monitor
    //   - write of a volatile        -> every later read of the SAME volatile
    //   - Thread.start()             -> the first action of the started thread
    //   - the last action of thread T -> another thread's T.join() returning
    //   - setting a final field in a constructor -> any thread that sees the
    //     object through a reference published after construction
    //   - java.util.concurrent: put/offer -> take/poll, submit -> task
    //     run, task done -> Future.get(), countDown -> await returning...
    // HB is TRANSITIVE: if A hb B and B hb C, then A hb C ("piggybacking").
    //
    // A DATA RACE = two accesses to the same variable, at least one a write,
    // NOT ordered by HB. Programs without data races behave as if sequentially
    // consistent (the "DRF-SC" guarantee). With races, surprising results are
    // LEGAL, as the litmus test below shows.
    // =========================================================================

    /** The classic "store buffering" (Dekker) litmus test. Each round i uses fresh slots. */
    interface Litmus {
        void actor1(int i);          // x = 1; r1 = y;
        void actor2(int i);          // y = 1; r2 = x;
        boolean bothZero(int i);     // r1 == 0 && r2 == 0
    }

    /*
     * With PLAIN fields, can BOTH threads read 0? Intuition says no: one of
     * the writes must have happened first. But each CPU can put its store
     * into its store buffer and run the LOAD before the store becomes
     * visible. Both loads then see 0. This happens even on x86, whose memory
     * model is otherwise "strong". The JIT may also reorder the independent
     * store and load by itself.
     */
    static final class PlainLitmus implements Litmus {
        final int[] x, y, r1, r2;                         // plain array elements
        PlainLitmus(int n) { x = new int[n]; y = new int[n]; r1 = new int[n]; r2 = new int[n]; }
        public void actor1(int i) { x[i] = 1; r1[i] = y[i]; }
        public void actor2(int i) { y[i] = 1; r2[i] = x[i]; }
        public boolean bothZero(int i) { return r1[i] == 0 && r2[i] == 0; }
    }

    /*
     * With VOLATILE x and y (AtomicIntegerArray get/set have volatile
     * semantics), all volatile accesses fall into one global total order that
     * respects program order (sequential consistency for volatiles).
     * Whichever write comes first in that order must be seen by the other
     * thread's later read, so (0, 0) is FORBIDDEN. The JIT emits a full
     * fence (on x86 a `lock addl`) after each volatile store to make that true.
     */
    static final class VolatileLitmus implements Litmus {
        final AtomicIntegerArray x, y;
        final int[] r1, r2;
        VolatileLitmus(int n) { x = new AtomicIntegerArray(n); y = new AtomicIntegerArray(n); r1 = new int[n]; r2 = new int[n]; }
        public void actor1(int i) { x.set(i, 1); r1[i] = y.get(i); }
        public void actor2(int i) { y.set(i, 1); r2[i] = x.get(i); }
        public boolean bothZero(int i) { return r1[i] == 0 && r2[i] == 0; }
    }

    /**
     * Runs `rounds` rounds. Before each round the two actor threads meet at a
     * spin barrier, so they leave it at nearly the same instant and their
     * actions really overlap. Returns how many rounds ended with (0, 0).
     */
    static int runLitmus(Litmus test, int rounds) throws InterruptedException {
        AtomicInteger arrivals = new AtomicInteger();
        Thread[] threads = new Thread[2];
        for (int a = 0; a < 2; a++) {
            boolean first = a == 0;
            threads[a] = new Thread(() -> {
                for (int i = 0; i < rounds; i++) {
                    arrivals.incrementAndGet();
                    int target = 2 * (i + 1), spins = 0;
                    while (arrivals.get() < target) {             // wait for the partner
                        if (++spins > 10_000) Thread.yield(); else Thread.onSpinWait();
                    }
                    if (first) test.actor1(i); else test.actor2(i);
                }
            });
            threads[a].start();
        }
        for (Thread t : threads) t.join();                        // join() = happens-before: safe to read results
        int bothZero = 0;
        for (int i = 0; i < rounds; i++) if (test.bothZero(i)) bothZero++;
        return bothZero;
    }

    /** Message passing with VarHandle release/acquire (Java 9), the cheapest correct publication. */
    static final class Mailbox {
        int payload;               // PLAIN field
        boolean ready;             // accessed only through the VarHandle
        static final VarHandle READY;

        static {
            try {
                READY = MethodHandles.lookup().findVarHandle(Mailbox.class, "ready", boolean.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /**
     * FINAL FIELD SEMANTICS: if a constructor sets final fields and `this`
     * does not escape during construction, then ANY thread that sees a
     * reference to the object sees the final fields fully initialized, even
     * if the reference itself was published through a data race. That's why
     * immutable objects (String, Integer, records...) are thread-safe with no
     * synchronization at all. Non-final fields carry no such guarantee: a
     * racy reader could see `count` as 0.
     */
    static final class ImmutablePoint {
        final int x, y;                     // guaranteed visible once the reference is seen
        ImmutablePoint(int x, int y) { this.x = x; this.y = y; }
    }

    static void section1_memoryModel(Path labDir) throws Exception {
        section("1. Java Memory Model");

        // --- Litmus test: plain fields (reordering is ALLOWED, so observe it)
        // Store-buffer reordering needs two threads running TRULY in parallel. On
        // one CPU the threads take turns (and a context switch drains the store
        // buffer), so (0, 0) can't appear, and spin barriers get slow. Scale down.
        int cpus = Runtime.getRuntime().availableProcessors();
        int rounds = cpus >= 2 ? 200_000 : 2_000;
        if (cpus < 2) System.out.println("      (only 1 CPU available: reordering needs 2+ cores running in parallel)");
        int plain = runLitmus(new PlainLitmus(rounds), rounds);
        System.out.printf("      plain fields:    (r1, r2) == (0, 0) in %,d of %,d rounds%n", plain, rounds);
        System.out.println("      (a non-zero count is store->load reordering caught live; it is LEGAL for plain fields)");

        // --- Litmus test: volatile fields (reordering is FORBIDDEN, so assert it)
        int vol = runLitmus(new VolatileLitmus(rounds), rounds);
        System.out.printf("      volatile fields: (r1, r2) == (0, 0) in %,d of %,d rounds%n", vol, rounds);
        check("volatile forbids the (0, 0) outcome", vol, 0);

        // --- VarHandle release/acquire message passing
        // setRelease: no earlier read/write may move AFTER it.
        // getAcquire: no later read/write may move BEFORE it.
        // Together: if the consumer sees ready == true, it MUST see payload == 42.
        // Cheaper than a full volatile write (no store-load fence).
        int wrong = 0;
        for (int i = 0; i < 500; i++) {
            Mailbox box = new Mailbox();
            int[] seen = new int[1];
            Thread consumer = new Thread(() -> {
                while (!(boolean) Mailbox.READY.getAcquire(box)) Thread.onSpinWait();
                seen[0] = box.payload;
            });
            consumer.start();
            box.payload = 42;                        // 1. plain write
            Mailbox.READY.setRelease(box, true);     // 2. release: publishes write 1
            consumer.join();
            if (seen[0] != 42) wrong++;
        }
        check("acquire/release: payload always visible", wrong, 0);

        // --- JIT HOISTING: a non-volatile flag read inside a hot loop
        // The JIT may compile `while (!stop) n++;` as `if (!stop) while (true) n++;`
        // because nothing in the loop writes `stop`. The thread then spins forever.
        // Shown in a CHILD JVM, so a stuck thread can't burn CPU in this one.
        String hoist = runLab(labDir, List.of(), "hoist");
        String stillRunning = extract(hoist, "HOIST_STILL_RUNNING=(\\w+)");
        System.out.println("      non-volatile stop flag, worker still spinning 1 s after stop=true? " + stillRunning
                + (stillRunning.equals("true") ? "  <- the JIT hoisted the read" : "  (not hoisted this run)"));
        check("hoisting experiment ran", stillRunning.equals("true") || stillRunning.equals("false"), true);

        // --- final fields: the guarantee holds for every reader thread
        ImmutablePoint[] shared = new ImmutablePoint[1];
        AtomicInteger torn = new AtomicInteger();
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 2_000_000; i++) {
                ImmutablePoint p = shared[0];                 // RACY read of the reference
                if (p != null && p.x != p.y) torn.incrementAndGet();
            }
        });
        reader.start();
        for (int i = 1; i <= 200_000; i++) shared[0] = new ImmutablePoint(i, i);   // racy publication
        reader.join();
        check("final fields never seen half-initialized", torn.get(), 0);

        // WORD TEARING: the JLS lets a NON-volatile long/double be written as two
        // 32-bit halves (JLS 17.7), so a racy reader could see half old, half
        // new. 64-bit JVMs write them atomically in practice, but the guarantee
        // only comes from volatile, AtomicLong, or locks.
        System.out.println("      word tearing: see the comment above (JLS 17.7). Not reproducible on 64-bit JVMs.");
    }

    // =========================================================================
    // 2. FAIL-FAST vs FAIL-SAFE ITERATORS
    // =========================================================================
    //
    // FAIL-FAST (ArrayList, HashMap, HashSet, LinkedList, TreeMap...)
    //   Each collection keeps a `modCount` that changes on every STRUCTURAL
    //   change (add/remove, not set). An iterator records it at creation and
    //   compares it on each next(). A mismatch throws
    //   ConcurrentModificationException (CME).
    //   - Happens in a SINGLE thread too: CME means "modified during
    //     iteration", not necessarily "multithreading".
    //   - It's BEST-EFFORT: modCount isn't volatile, so a CME between threads
    //     isn't guaranteed. Never rely on it for correctness, only for
    //     catching bugs early.
    //   - The safe ways to remove: Iterator.remove(), removeIf(), or collect
    //     the removals and apply them after the loop.
    //
    // FAIL-SAFE (java.util.concurrent):
    //   - SNAPSHOT iterators (CopyOnWriteArrayList / CopyOnWriteArraySet):
    //     iterate over the array as it was when the iterator was created.
    //     They never throw and never see later changes. iterator.remove()
    //     is NOT supported.
    //   - WEAKLY CONSISTENT iterators (ConcurrentHashMap,
    //     ConcurrentLinkedQueue, ConcurrentSkipListMap...): never throw, each
    //     element is returned at most once, and later changes MAY or MAY NOT
    //     be seen.
    // =========================================================================

    static void section2_failFastFailSafe() {
        section("2. Fail-fast vs fail-safe iterators");

        // Fail-fast in a SINGLE thread
        List<String> loans = new ArrayList<>(List.of("a", "b", "c", "d"));
        check("ArrayList: remove during for-each",
                throwsName(() -> { for (String s : loans) if (s.equals("a")) loans.remove(s); }),
                "ConcurrentModificationException");

        Map<String, Integer> stock = new HashMap<>(Map.of("x", 1, "y", 2, "z", 3));
        check("HashMap: put during keySet iteration",
                throwsName(() -> { for (String k : stock.keySet()) stock.put(k + "!", 0); }),
                "ConcurrentModificationException");

        // set() is NOT structural: modCount doesn't change, so no CME
        List<Integer> nums = new ArrayList<>(List.of(1, 2, 3));
        for (int i = 0; i < nums.size(); i++) nums.set(i, nums.get(i) * 10);
        ListIterator<Integer> li = nums.listIterator();
        while (li.hasNext()) li.set(li.next() + 1);
        check("set() during iteration is allowed", nums, List.of(11, 21, 31));

        // THE QUIRK: removing the SECOND-TO-LAST element does NOT throw.
        // After the removal, hasNext() checks cursor != size: cursor 2, size 2,
        // so the loop ENDS without calling next(), and next() is where the
        // check lives. The last element is silently never visited.
        List<String> quirk = new ArrayList<>(List.of("a", "b", "c"));
        List<String> visited = new ArrayList<>();
        String outcome = throwsName(() -> {
            for (String s : quirk) {
                visited.add(s);
                if (s.equals("b")) quirk.remove(s);
            }
        });
        check("second-to-last removal: no CME!", outcome, "no exception");
        check("...and 'c' was silently skipped", visited, List.of("a", "b"));

        // Correct removal
        List<String> fixed = new ArrayList<>(List.of("a", "b", "c", "d"));
        for (Iterator<String> it = fixed.iterator(); it.hasNext(); ) if (it.next().compareTo("b") <= 0) it.remove();
        check("Iterator.remove()", fixed, List.of("c", "d"));
        fixed.removeIf(s -> s.equals("c"));
        check("removeIf()", fixed, List.of("d"));

        // synchronizedList is thread-safe per CALL, but its iterator is STILL fail-fast.
        // Iterating it requires synchronized (list) { ... } around the whole loop.
        List<String> syncList = Collections.synchronizedList(new ArrayList<>(List.of("a", "b", "c", "d")));
        check("synchronizedList iterator is still fail-fast",
                throwsName(() -> { for (String s : syncList) syncList.add("x"); }),
                "ConcurrentModificationException");

        // FAIL-SAFE: snapshot iterator
        CopyOnWriteArrayList<String> listeners = new CopyOnWriteArrayList<>(List.of("audit", "email"));
        Iterator<String> snapshot = listeners.iterator();
        listeners.add("sms");                             // added AFTER the iterator was created
        List<String> seenBySnapshot = new ArrayList<>();
        snapshot.forEachRemaining(seenBySnapshot::add);
        check("COW snapshot ignores later adds", seenBySnapshot, List.of("audit", "email"));
        check("COW iterator.remove() unsupported",
                throwsName(() -> { Iterator<String> it = listeners.iterator(); it.next(); it.remove(); }),
                "UnsupportedOperationException");

        // FAIL-SAFE: weakly consistent iterator
        ConcurrentHashMap<String, Integer> sessions = new ConcurrentHashMap<>();
        for (int i = 0; i < 10; i++) sessions.put("s" + i, i);
        int iterated = 0;
        for (String key : sessions.keySet()) {
            sessions.put(key + "-renewed", 0);            // modify while iterating: allowed
            iterated++;
        }
        check("CHM: no CME while modifying", sessions.size() >= 20, true);
        System.out.printf("      CHM iterator visited %d keys (between 10 and %d; new keys may or may not show)%n",
                iterated, sessions.size());
        check("CHM visited at least the original keys", iterated >= 10, true);
    }

    // =========================================================================
    // 3. MEMORY AREAS: HEAP vs STACK (and the rest)
    // =========================================================================
    //
    //  AREA             SHARED?      HOLDS                                 ERROR WHEN FULL
    //  --------------   ----------   -----------------------------------   -------------------------------
    //  Heap             all threads  every object and array; static        OOM: Java heap space
    //                                fields (inside the Class mirror,
    //                                since Java 8); the String pool
    //                                (since Java 7)
    //  Thread stack     per thread   one FRAME per method call: local      StackOverflowError
    //                                primitives, REFERENCES (not the
    //                                objects), operand stack, return
    //                                address
    //  Metaspace        all threads  class metadata, method bytecode,      OOM: Metaspace
    //                                constant pools (native memory;
    //                                replaced PermGen in Java 8)
    //  Code cache       all threads  JIT-compiled machine code             "CodeCache is full" warning
    //  Direct memory    all threads  ByteBuffer.allocateDirect, NIO        OOM: Direct buffer memory
    //  PC register      per thread   the bytecode instruction now running  -
    //  Native stack     per thread   frames of native (JNI) methods        -
    //
    // KEY POINTS
    //   - `Order o = new Order();`: the REFERENCE o lives in the stack frame;
    //     the Order OBJECT lives on the heap.
    //   - Stack memory is freed instantly when a method returns (pop the
    //     frame). Heap memory is freed only by the GARBAGE COLLECTOR.
    //   - Locals are thread-confined, so inherently thread-safe. Heap objects
    //     are shared once a reference escapes to another thread.
    //   - ESCAPE ANALYSIS: if the JIT proves an object never escapes a method,
    //     it may skip the heap allocation entirely ("scalar replacement" keeps
    //     its fields in registers or on the stack).
    //
    // OBJECT LAYOUT (64-bit HotSpot): a header made of the MARK WORD (8 bytes:
    // identity hash, lock state, GC age) and the KLASS POINTER (4 bytes with
    // compressed class pointers). Then the fields, padded to a multiple of 8.
    // An empty object is 16 bytes; an Integer is 16 bytes, versus 4 for an
    // int. (Compact Object Headers, JEP 450 / JDK 24+, shrink the header to
    // 8 bytes.)
    // =========================================================================

    static int depth;

    static void recurse() {
        depth++;
        recurse();                       // each call pushes a new stack frame
    }

    static int measureStackDepth(long stackSizeBytes) throws InterruptedException {
        int[] result = new int[1];
        // The Thread constructor's stackSize is a HINT; HotSpot honors it on mainstream platforms
        Thread t = new Thread(null, () -> {
            depth = 0;
            try {
                recurse();
            } catch (StackOverflowError e) {
                result[0] = depth;       // catching SOE is OK for a demo; never do it in production
            }
        }, "stack-" + stackSizeBytes, stackSizeBytes);
        t.start();
        t.join();
        return result[0];
    }

    static void section3_memoryAreas() throws Exception {
        section("3. Memory areas: heap vs stack, metaspace, direct memory");

        // --- STACK: depth until StackOverflowError, for two stack sizes
        int small = measureStackDepth(512 * 1024);
        int large = measureStackDepth(8 * 1024 * 1024);
        System.out.printf("      recursion depth before StackOverflowError: 512 KB stack = %,d frames, 8 MB stack = %,d frames%n", small, large);
        check("bigger stack => deeper recursion", large > small * 2, true);

        // --- Locals are per-thread (on each thread's own stack): no race possible
        int[] sharedOnHeap = new int[1];                    // ONE array object on the heap, shared
        int[] perThreadTotals = new int[4];
        Thread[] ts = new Thread[4];
        for (int t = 0; t < 4; t++) {
            int id = t;
            ts[t] = new Thread(() -> {
                int local = 0;                               // lives in THIS thread's stack frame
                for (int i = 0; i < 100_000; i++) {
                    local++;
                    sharedOnHeap[0]++;                       // a data race on a heap location
                }
                perThreadTotals[id] = local;
            });
            ts[t].start();
        }
        for (Thread t : ts) t.join();
        check("stack locals: every thread counted exactly", Arrays.stream(perThreadTotals).sum(), 400_000);
        System.out.printf("      shared heap counter (unsynchronized): %,d of 400,000%n", sharedOnHeap[0]);

        // --- Heap figures from Runtime
        Runtime rt = Runtime.getRuntime();
        System.out.printf("      heap: max=%,d MB  committed=%,d MB  free=%,d MB%n",
                rt.maxMemory() >> 20, rt.totalMemory() >> 20, rt.freeMemory() >> 20);
        check("max >= committed >= free", rt.maxMemory() >= rt.totalMemory() && rt.totalMemory() >= rt.freeMemory(), true);

        // --- Memory pools: the actual regions, which depend on the GC in use
        System.out.println("      memory pools in this JVM:");
        boolean hasHeap = false, hasMetaspace = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            System.out.printf("        %-9s %-34s used=%,d KB%n", pool.getType(), pool.getName(), pool.getUsage().getUsed() >> 10);
            hasHeap |= pool.getType() == MemoryType.HEAP;
            hasMetaspace |= pool.getName().equals("Metaspace");
        }
        check("heap and Metaspace pools exist", hasHeap && hasMetaspace, true);

        // --- DIRECT (off-heap) memory: allocated outside the Java heap
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(b -> b.getName().equals("direct")).findFirst().orElseThrow();
        long before = direct.getMemoryUsed();
        ByteBuffer offHeap = ByteBuffer.allocateDirect(8 << 20);       // 8 MB, NOT on the Java heap
        long after = direct.getMemoryUsed();
        check("allocateDirect uses native memory", after - before >= 8 << 20, true);
        check("direct buffer has no backing heap array", offHeap.hasArray(), false);

        // --- One OutOfMemoryError kind that is safe to trigger in-process: the
        // JVM refuses before allocating anything.
        String oomMessage;
        try {
            long[] impossible = new long[Integer.MAX_VALUE];
            oomMessage = "allocated " + impossible.length;
        } catch (OutOfMemoryError e) {
            oomMessage = e.getMessage();
        }
        check("OOM: requested array size", oomMessage, "Requested array size exceeds VM limit");
        // The other OOM kinds ("Java heap space", "Direct buffer memory") are
        // triggered in child JVMs in section 5. Others: "Metaspace",
        // "GC overhead limit exceeded", "unable to create native thread".
    }

    // =========================================================================
    // 4. GC FUNDAMENTALS: REACHABILITY AND REFERENCE TYPES
    // =========================================================================
    //
    // Java GC is based on REACHABILITY, not reference counting. Starting from
    // the GC ROOTS, the collector marks everything reachable; the rest is
    // garbage. The GC roots are:
    //   - local variables and operands in every live thread's stack frames
    //   - static fields of loaded classes
    //   - active Java threads themselves
    //   - JNI references, and monitors held by synchronized blocks
    // Because it traces from roots, CYCLES (A <-> B with nothing else pointing
    // at them) ARE collected. Reference counting could never free them.
    //
    // REFERENCE STRENGTH (java.lang.ref), strongest to weakest:
    //   STRONG    normal references. Never collected while reachable.
    //   SOFT      cleared only when memory is tight (always before an
    //             OutOfMemoryError). Meant for memory-sensitive caches, but
    //             tuning is hard; prefer a real cache (Caffeine) in production.
    //   WEAK      cleared at the next GC once only weakly reachable. Used by
    //             WeakHashMap and canonicalizing maps.
    //   PHANTOM   get() always returns null. Enqueued after the object is
    //             finalized/unreachable, for post-mortem cleanup. Cleaner uses it.
    // A ReferenceQueue tells you WHEN a reference was cleared.
    //
    // System.gc() is only a REQUEST. -XX:+DisableExplicitGC turns it off
    // entirely. The demos below retry a few times to stay reliable.
    // =========================================================================

    static boolean gcUntil(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            if (condition.getAsBoolean()) return true;
            System.gc();
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    static final class Node {
        Node partner;
        final byte[] payload = new byte[1024];
    }

    /** Outer class with an INNER (non-static) class: every inner instance holds Outer.this. */
    static final class ReportPage {
        final byte[] bigData = new byte[4 << 20];       // 4 MB the page "owns"

        // INNER class: holds a hidden reference (this$0) to ReportPage.this.
        // SUBTLETY: since JDK 18, javac OMITS this$0 when the inner class never
        // uses the outer instance (JDK-8271623). This handler reads bigData, so
        // the reference is kept, and the leak below is real.
        final class ClickHandler {
            String handle() { return "clicked on a page of " + bigData.length + " bytes"; }
        }

        static final class StaticHandler {              // static: NO hidden reference
            String handle() { return "clicked"; }
        }
    }

    static final Cleaner CLEANER = Cleaner.create();

    /**
     * Cleaner (Java 9) replaces finalize(), deprecated for removal (JEP 421).
     * finalize() was unpredictable, slow, could RESURRECT objects, and its
     * exceptions were swallowed. Rules for Cleaner:
     *   - the cleanup action must NOT reference the object itself (or it can
     *     never become unreachable), so use a static nested class or a
     *     captured-state lambda
     *   - still offer close() (AutoCloseable) for deterministic cleanup. The
     *     Cleaner is only the SAFETY NET.
     */
    static final class NativeConnection implements AutoCloseable {
        private static final class State implements Runnable {   // holds ONLY what cleanup needs
            final AtomicBoolean released;
            State(AtomicBoolean released) { this.released = released; }
            @Override public void run() { released.set(true); }  // e.g. free a native handle
        }

        private final Cleaner.Cleanable cleanable;

        NativeConnection(AtomicBoolean releasedFlag) {
            this.cleanable = CLEANER.register(this, new State(releasedFlag));
        }

        @Override public void close() { cleanable.clean(); }      // explicit, runs at most once
    }

    @SuppressWarnings("try")   // `conn` exists only to be closed
    static void section4_gcFundamentals() throws Exception {
        section("4. GC fundamentals: reachability, references, leaks");

        // --- STRONG vs WEAK
        Object strong = new byte[1024];
        WeakReference<Object> weakToStrong = new WeakReference<>(strong);
        WeakReference<Object> weakOnly = new WeakReference<>(new byte[1024]);   // no strong ref at all
        check("weakly-reachable object is collected", gcUntil(() -> weakOnly.get() == null), true);
        check("strongly-reachable object survives", weakToStrong.get() != null, true);
        Reference.reachabilityFence(strong);   // keep `strong` alive up to here (the JIT can end liveness early!)

        // --- CYCLES are collected (tracing GC, not reference counting)
        Node a = new Node(), b = new Node();
        a.partner = b;
        b.partner = a;                                   // a <-> b
        WeakReference<Node> cycleWatch = new WeakReference<>(a);
        a = null;
        b = null;                                        // the cycle is now an "island of isolation"
        check("unreachable cycle is collected", gcUntil(() -> cycleWatch.get() == null), true);

        // --- ReferenceQueue: get NOTIFIED when an object is collected
        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> tracked = new WeakReference<>(new Object(), queue);
        gcUntil(() -> tracked.get() == null);
        check("cleared reference is enqueued", queue.remove(2_000) == tracked, true);

        // --- PHANTOM references: get() is always null
        ReferenceQueue<Object> phantomQueue = new ReferenceQueue<>();
        Object resource = new Object();
        PhantomReference<Object> phantom = new PhantomReference<>(resource, phantomQueue);
        check("PhantomReference.get() is always null", phantom.get(), null);
        resource = null;
        gcUntil(() -> false);                            // force a few GCs
        check("phantom enqueued after collection", phantomQueue.remove(2_000) == phantom, true);

        // --- WeakHashMap: entries vanish when the KEY is no longer strongly reachable
        // (Classic use: attach metadata to objects you don't own, without leaking them.)
        Map<Object, String> metadata = new WeakHashMap<>();
        Object sessionKey = new Object();
        metadata.put(sessionKey, "session data");
        metadata.put(new Object(), "orphaned");          // key unreachable immediately
        check("WeakHashMap drops orphaned entries", gcUntil(() -> metadata.size() == 1), true);
        Reference.reachabilityFence(sessionKey);

        // --- CLEANER: safety-net cleanup when an object is collected without close()
        AtomicBoolean releasedExplicitly = new AtomicBoolean();
        try (NativeConnection conn = new NativeConnection(releasedExplicitly)) {
            // use the connection
        }
        check("close() releases deterministically", releasedExplicitly.get(), true);

        AtomicBoolean releasedByGc = new AtomicBoolean();
        new NativeConnection(releasedByGc);              // forgot to close(): leaked!
        check("Cleaner released the leaked one after GC", gcUntil(releasedByGc::get), true);

        // --- MEMORY LEAK: an inner-class instance keeps its OUTER object alive
        ReportPage page = new ReportPage();
        ReportPage.ClickHandler handler = page.new ClickHandler();    // e.g. registered as a listener
        WeakReference<ReportPage> pageWatch = new WeakReference<>(page);
        page = null;                                     // we think the page is gone...
        gcUntil(() -> pageWatch.get() == null);
        check("LEAK: inner handler pins the 4 MB page", pageWatch.get() != null, true);
        handler.handle();                                // the handler is still in use
        handler = null;                                  // unregister the listener
        check("page collected once the handler is dropped", gcUntil(() -> pageWatch.get() == null), true);
        // FIX: make listener classes static nested (ReportPage.StaticHandler) or use weak listeners.
        // Other classic leaks: ever-growing static collections or caches, unremoved
        // ThreadLocals in pools, unclosed resources, ClassLoader leaks in app servers.

        // --- GC activity in THIS JVM, through MXBeans
        long gcsBefore = totalGcCount();
        System.gc();
        long gcsAfter = totalGcCount();
        System.out.print("      collectors in this JVM:");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            System.out.printf(" [%s: %d runs, %d ms]", gc.getName(), gc.getCollectionCount(), gc.getCollectionTime());
        }
        System.out.println();
        check("System.gc() triggered a collection", gcsAfter > gcsBefore, true);
    }

    static long totalGcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(c -> c > 0).sum();
    }

    // =========================================================================
    // 5. GC ALGORITHMS AND THE HOTSPOT COLLECTORS
    // =========================================================================
    //
    // WEAK GENERATIONAL HYPOTHESIS: most objects die young. So the heap is split:
    //   YOUNG GEN = Eden (new objects) + 2 Survivor spaces (S0/S1)
    //   OLD GEN   = objects that survived N young collections ("tenured"; the
    //               threshold is MaxTenuringThreshold, usually up to 15)
    //   - A MINOR (young) GC copies live Eden/survivor objects to the other
    //     survivor space (or promotes them to old) and wipes Eden in one go.
    //     Cost is proportional to LIVE objects, not garbage, which is why
    //     short-lived garbage is so cheap in Java.
    //   - A MAJOR / FULL GC collects the old gen too, and is much more expensive.
    //
    // BASIC ALGORITHMS
    //   Mark-Sweep    mark live objects, free the rest. Leaves FRAGMENTATION.
    //   Mark-Compact  then slide live objects together. No fragmentation, but
    //                 objects move.
    //   Copying       evacuate live objects into an empty region. Fast, and
    //                 compacts as a side effect; needs spare space.
    //
    // MACHINERY
    //   TLAB          Thread-Local Allocation Buffer: each thread allocates by
    //                 bumping a pointer in its own Eden chunk (about 10 machine
    //                 instructions, no lock). `new` is very cheap.
    //   Write barrier code the JIT adds on reference stores. It tracks
    //                 old->young pointers in a CARD TABLE (so a minor GC
    //                 needn't scan the whole old gen) and keeps concurrent
    //                 marking correct.
    //   Safepoint     a point where all Java threads are paused so the GC
    //                 sees a consistent heap: "stop-the-world" (STW).
    //   Concurrent    collectors that mark (and even compact) WHILE the
    //                 application runs, using tri-color marking + barriers.
    //
    // THE COLLECTORS (pick with a flag; G1 is the default since JDK 9)
    //   Serial      -XX:+UseSerialGC     single-threaded, STW. Tiny heaps,
    //                                    containers with 1 CPU. Also the JVM's
    //                                    automatic choice on small machines.
    //   Parallel    -XX:+UseParallelGC   multi-threaded, STW. Best THROUGHPUT
    //                                    for batch jobs that tolerate pauses.
    //                                    (Default in JDK 8.)
    //   G1          -XX:+UseG1GC         heap split into equal REGIONS;
    //                                    collects the regions with the most
    //                                    garbage first. Concurrent marking +
    //                                    STW evacuation, aiming at a pause
    //                                    target (-XX:MaxGCPauseMillis=200).
    //                                    A balanced default.
    //   ZGC         -XX:+UseZGC          concurrent marking AND compaction
    //                                    using colored pointers + load
    //                                    barriers. Sub-millisecond pauses
    //                                    regardless of heap size (up to
    //                                    16 TB). Generational in JDK 21 with
    //                                    -XX:+ZGenerational, the default mode
    //                                    since JDK 23.
    //   Shenandoah  -XX:+UseShenandoahGC concurrent compaction with forwarding
    //                                    pointers. Low pauses (Red Hat; in
    //                                    most OpenJDK builds).
    //   Epsilon     -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC
    //                                    a NO-OP collector: allocates, never
    //                                    frees. For benchmarks and very
    //                                    short-lived jobs.
    //   CMS         removed in JDK 14 (deprecated in 9). Replaced by G1/ZGC.
    //
    // CHOOSING: start with G1. Need tiny pauses on a big heap: ZGC.
    // Throughput-only batch work: Parallel. Tiny container: Serial.
    // =========================================================================

    static void section5_collectors(Path labDir) throws Exception {
        section("5. Garbage collectors: started for real in child JVMs");

        // --- Start a JVM with each collector and read back which one is active
        Map<String, List<String>> collectors = new LinkedHashMap<>();
        collectors.put("Serial", List.of("-XX:+UseSerialGC"));
        collectors.put("Parallel", List.of("-XX:+UseParallelGC"));
        collectors.put("G1 (default)", List.of("-XX:+UseG1GC"));
        collectors.put("ZGC (generational)", List.of("-XX:+UseZGC", "-XX:+ZGenerational"));
        collectors.put("Shenandoah", List.of("-XX:+UseShenandoahGC"));
        collectors.put("Epsilon (no-op)", List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseEpsilonGC"));

        Map<String, String> detected = new LinkedHashMap<>();
        for (var e : collectors.entrySet()) {
            List<String> cmd = new ArrayList<>(e.getValue());
            cmd.addAll(List.of("-Xlog:gc", "-version"));
            String out = runJvm(cmd);
            Matcher m = Pattern.compile("Using (.+)").matcher(out);
            String result = m.find() ? m.group(1).trim() : "not available in this JDK build";
            detected.put(e.getKey(), result);
            System.out.printf("      %-20s -> JVM reports: %s%n", e.getKey(), result);
        }
        check("Serial GC available", detected.get("Serial"), "Serial");
        check("Parallel GC available", detected.get("Parallel"), "Parallel");
        check("G1 GC available", detected.get("G1 (default)"), "G1");

        // --- A LIVE GC LOG: allocation churn in a 32 MB heap with Serial GC
        String churn = runLab(labDir, List.of("-Xmx32m", "-XX:+UseSerialGC", "-Xlog:gc"), "churn");
        List<String> pauses = churn.lines().filter(l -> l.contains("Pause Young")).toList();
        System.out.printf("      768 MB of short-lived garbage in a 32 MB heap caused %d young GCs, e.g.:%n", pauses.size());
        pauses.stream().limit(3).forEach(l -> System.out.println("        " + l));
        check("young GCs reclaim short-lived garbage", pauses.size() > 5 && churn.contains("CHURN_DONE"), true);

        // --- SOFT references are cleared under memory pressure, BEFORE an OOM
        String soft = runLab(labDir, List.of("-Xmx64m"), "soft");
        int alive = Integer.parseInt(extract(soft, "SOFT_ALIVE=(\\d+)"));
        System.out.printf("      cached 200 x 1 MB via SoftReference in a 64 MB heap: %d still alive, no OOM%n", alive);
        check("soft refs cleared instead of OOM", alive < 200 && soft.contains("SOFT_OK"), true);

        // --- OOM: Java heap space (strong references can't be cleared)
        String heapOom = runLab(labDir, List.of("-Xmx32m"), "heap-oom");
        System.out.println("      strong references in a 32 MB heap: " + extract(heapOom, "(OOM_AFTER_MB=.*)"));
        check("OOM: Java heap space", extract(heapOom, "MSG=(.*)"), "Java heap space");

        // --- OOM: Direct buffer memory (an off-heap limit)
        String directOom = runLab(labDir, List.of("-XX:MaxDirectMemorySize=8m"), "direct-oom");
        check("OOM: direct buffer memory", extract(directOom, "DIRECT_OOM=(.*)").contains("direct buffer memory"), true);
    }

    // =========================================================================
    // 6. CLASS LOADING
    // =========================================================================
    //
    // LOADER HIERARCHY (JDK 9+):
    //   Bootstrap  (native, shown as null)  java.base: String, Object...
    //     └─ Platform loader                java.sql, java.xml...
    //          └─ Application (system) loader   your classpath / module path
    //               └─ custom loaders       plugins, app servers, hot reload
    //
    // PARENT DELEGATION: a loader first asks its PARENT. Only if the parent
    // can't find the class does it look itself. So nobody can replace
    // java.lang.String with their own version.
    //
    // CLASS IDENTITY = fully qualified name + DEFINING loader. The same .class
    // file loaded by two loaders gives two DIFFERENT, incompatible classes
    // (casting between them throws ClassCastException). That isolation is how
    // app servers run two versions of a library side by side.
    //
    // LIFECYCLE: Loading -> Linking (verify, prepare statics with default
    // values, resolve) -> INITIALIZATION (run static initializers), which is
    // LAZY. It happens on first "active use": new instance, static method call,
    // a non-constant static field read or write, Class.forName(name), or the
    // initialization of a subclass. These do NOT initialize a class:
    //   - reading a compile-time CONSTANT (static final primitive/String
    //     with a constant value): it's inlined at compile time
    //   - creating an array of the type
    //   - a class literal (Foo.class)
    //   - Class.forName(name, false, loader)
    // =========================================================================

    static final List<String> INIT_LOG = new ArrayList<>();

    static final class LazyConfig {
        static final int MAX_RETRIES = 3;                              // compile-time constant
        static final List<String> REGIONS = List.of("us-east", "eu");  // NOT a constant
        static { INIT_LOG.add("LazyConfig initialized"); }

        static String region() { return REGIONS.getFirst(); }
    }

    static void section6_classLoading(Path labDir) throws Exception {
        section("6. Class loading: hierarchy, identity, lazy init");

        check("java.base classes: bootstrap (null)", String.class.getClassLoader(), null);
        check("java.sql classes: platform loader", java.sql.Connection.class.getClassLoader(), ClassLoader.getPlatformClassLoader());
        check("platform's parent is bootstrap (null)", ClassLoader.getPlatformClassLoader().getParent(), null);
        System.out.println("      this class was loaded by: " + JvmInternalsShowcase.class.getClassLoader().getClass().getName());

        // --- LAZY INITIALIZATION
        int retries = LazyConfig.MAX_RETRIES;                  // a constant: inlined, no init
        LazyConfig[] array = new LazyConfig[5];                // an array: no init
        Class<?> literal = LazyConfig.class;                   // a class literal: no init
        Class.forName(LazyConfig.class.getName(), false, LazyConfig.class.getClassLoader());   // initialize=false
        check("constant/array/literal/forName(false): no init", INIT_LOG.isEmpty() && retries == 3 && array.length == 5 && literal != null, true);
        String region = LazyConfig.region();                   // static method call: INITIALIZES the class
        check("first active use triggers init", INIT_LOG, List.of("LazyConfig initialized"));
        LazyConfig.region();
        check("static init runs exactly once", INIT_LOG.size() + " " + region, "1 us-east");

        // --- CLASS IDENTITY: the same bytes, two loaders, two different classes
        URL labUrl = labDir.toUri().toURL();
        try (URLClassLoader loader1 = new URLClassLoader(new URL[]{labUrl}, ClassLoader.getPlatformClassLoader());
             URLClassLoader loader2 = new URLClassLoader(new URL[]{labUrl}, ClassLoader.getPlatformClassLoader())) {
            Class<?> c1 = loader1.loadClass("GcLab");
            Class<?> c2 = loader2.loadClass("GcLab");
            check("same name", c1.getName().equals(c2.getName()), true);
            check("...but different Class objects", c1 == c2, false);
            Object fromLoader1 = c1.getDeclaredConstructor().newInstance();
            check("instance of loader1's GcLab is NOT a loader2 GcLab", c2.isInstance(fromLoader1), false);
            check("casting across loaders fails", throwsName(() -> c2.cast(fromLoader1)), "ClassCastException");
            check("same loader returns the cached class", loader1.loadClass("GcLab") == c1, true);
        }
    }

    // =========================================================================
    // 7. HashMap INTERNALS
    // =========================================================================
    //
    //   - Backed by an array of BUCKETS (table); capacity is always a power of 2.
    //   - put(k, v):
    //       h = k.hashCode() ^ (h >>> 16)   "spread": mixes high bits into low bits
    //       index = h & (capacity - 1)      fast modulo for a power-of-2 capacity
    //     A bucket holds a linked list of nodes. Collisions are appended (tail
    //     insertion since Java 8).
    //   - TREEIFICATION (Java 8): a bucket with >= 8 nodes (when capacity >= 64)
    //     becomes a red-black TREE, so worst-case lookup is O(log n) instead of
    //     O(n). It turns back into a list at <= 6. Trees order keys by hash,
    //     then by compareTo if the keys are Comparable.
    //   - RESIZE: when size > capacity * loadFactor (0.75 by default), the
    //     table doubles and each node moves to index i or i + oldCapacity.
    //     Pre-size with `new HashMap<>(expected * 4 / 3 + 1)` or
    //     HashMap.newHashMap(expected) (Java 19).
    //   - Not thread-safe. In Java 7, concurrent resizes could even form a
    //     cycle and spin forever. Use ConcurrentHashMap.
    //   - One null key is allowed (stored in bucket 0).
    // =========================================================================

    /** A key whose hashCode ALWAYS collides: the worst case. */
    record CollidingKey(int id) implements Comparable<CollidingKey> {
        @Override public int hashCode() { return 42; }      // every key lands in ONE bucket
        @Override public int compareTo(CollidingKey o) { return Integer.compare(id, o.id); }
    }

    static int spread(Object key) {
        int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    static void section7_hashMapInternals() {
        section("7. HashMap internals: hashing, collisions, treeification");

        // Different strings with the SAME hashCode are common
        check("\"Aa\" and \"BB\" collide", "Aa".hashCode() == "BB".hashCode(), true);
        Map<String, Integer> m = new HashMap<>();
        m.put("Aa", 1);
        m.put("BB", 2);                                      // same bucket, told apart by equals()
        check("collisions resolved by equals()", m.get("Aa") + "," + m.get("BB"), "1,2");

        // Bucket index for a 16-slot table
        int capacity = 16;
        // For a power-of-2 capacity, `h & (n - 1)` equals the (non-negative) h mod n, minus the division
        check("bucket index: h & (n-1) == floorMod(h, n)",
                spread("order-17") & (capacity - 1), Math.floorMod(spread("order-17"), capacity));

        // WORST CASE: 20,000 keys in ONE bucket. Treeification keeps it fast and correct.
        Map<CollidingKey, Integer> worst = new HashMap<>();
        for (int i = 0; i < 20_000; i++) worst.put(new CollidingKey(i), i);
        long t0 = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < 20_000; i++) sum += worst.get(new CollidingKey(i));
        long micros = (System.nanoTime() - t0) / 1_000;
        System.out.printf("      20,000 lookups in a single treeified bucket took %,d us (O(log n) each)%n", micros);
        check("all colliding keys retrievable", sum, 20_000L * 19_999 / 2);

        // One null key
        Map<String, String> withNull = new HashMap<>();
        withNull.put(null, "allowed");
        check("HashMap allows one null key", withNull.get(null), "allowed");
        check("ConcurrentHashMap rejects null keys",
                throwsName(() -> new ConcurrentHashMap<String, String>().put(null, "x")), "NullPointerException");

        // Pre-sizing (Java 19+): no resizes while filling 1,000 entries
        Map<Integer, Integer> presized = HashMap.newHashMap(1_000);
        for (int i = 0; i < 1_000; i++) presized.put(i, i);
        check("HashMap.newHashMap(expected)", presized.size(), 1_000);
    }

    // =========================================================================
    // 8. SERIALIZATION AND CLONING
    // =========================================================================
    //
    // SERIALIZATION turns an object graph into bytes (ObjectOutputStream) and
    // back (ObjectInputStream).
    //   - Implement Serializable, a MARKER interface with no methods.
    //   - serialVersionUID: a version stamp. If it's missing, one is computed
    //     from the class shape, so ANY change breaks old data with
    //     InvalidClassException. Always declare it.
    //   - transient fields are skipped (they come back as null/0/false), and
    //     static fields aren't serialized at all.
    //   - Deserialization does NOT call your constructor. It can build objects
    //     that break invariants, so validate in readObject.
    //   - readResolve() swaps in another object after deserialization (keeps
    //     singletons singular). Enums handle this automatically.
    //   - SECURITY: deserializing untrusted data has caused many remote-code
    //     exploits. Use ObjectInputFilter (Java 9), or prefer JSON/Protobuf.
    //
    // CLONING: Object.clone() makes a SHALLOW field-by-field copy (only if the
    // class implements the Cloneable marker; otherwise it throws
    // CloneNotSupportedException). Mutable fields end up SHARED between the
    // original and the copy. Effective Java recommends copy constructors or
    // factories instead.
    // =========================================================================

    static final class CustomerAccount implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        static int instancesCreated = 0;                  // static: not part of the serialized form

        private final String owner;
        private final long balanceCents;
        private transient String sessionToken;            // transient: never written to the stream
        private final ArrayList<String> tags;             // a concrete Serializable type (List itself isn't)

        CustomerAccount(String owner, long balanceCents, String sessionToken, List<String> tags) {
            if (balanceCents < 0) throw new IllegalArgumentException("negative balance");
            this.owner = owner;
            this.balanceCents = balanceCents;
            this.sessionToken = sessionToken;
            this.tags = new ArrayList<>(tags);
            instancesCreated++;
        }

        /** Deserialization skips the constructor, so re-check the invariants here. */
        @Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            if (balanceCents < 0) throw new InvalidObjectException("negative balance in stream");
        }
    }

    /** A singleton that stays a singleton across serialization, thanks to readResolve. */
    static final class Currency implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        static final Currency USD = new Currency("USD");
        private final String code;

        private Currency(String code) { this.code = code; }

        @Serial
        private Object readResolve() { return code.equals("USD") ? USD : this; }
    }

    /** The same singleton WITHOUT readResolve: deserialization creates a duplicate. */
    static final class BrokenCurrency implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        static final BrokenCurrency EUR = new BrokenCurrency();
        private BrokenCurrency() {}
    }

    static final class Basket implements Cloneable {
        String owner;
        List<String> items;

        Basket(String owner, List<String> items) {
            this.owner = owner;
            this.items = items;
        }

        Basket shallowClone() throws CloneNotSupportedException {
            return (Basket) super.clone();             // copies the REFERENCE to items
        }

        Basket deepClone() throws CloneNotSupportedException {
            Basket copy = (Basket) super.clone();
            copy.items = new ArrayList<>(items);       // copy mutable state too
            return copy;
        }

        Basket(Basket other) {                         // a COPY CONSTRUCTOR: the preferred alternative
            this(other.owner, new ArrayList<>(other.items));
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T roundTrip(T obj) throws IOException, ClassNotFoundException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(obj);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            // Defense in depth: only allow the classes we expect (ObjectInputFilter, Java 9)
            in.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                    "JvmInternalsShowcase$*;java.util.*;java.lang.*;!*"));
            return (T) in.readObject();
        }
    }

    static void section8_serializationAndCloning() throws Exception {
        section("8. Serialization and cloning");

        var original = new CustomerAccount("Ana", 12_500, "secret-token", List.of("vip"));
        int createdBefore = CustomerAccount.instancesCreated;
        var copy = roundTrip(original);
        check("fields restored", copy.owner + " " + copy.balanceCents, "Ana 12500");
        check("transient field not serialized", copy.sessionToken, null);
        check("constructor NOT called on deserialize", CustomerAccount.instancesCreated, createdBefore);
        check("deep copy: new list instance", copy.tags != original.tags && copy.tags.equals(original.tags), true);

        check("readResolve preserves the singleton", roundTrip(Currency.USD) == Currency.USD, true);
        check("without readResolve: a duplicate appears", roundTrip(BrokenCurrency.EUR) == BrokenCurrency.EUR, false);

        // Cloning
        Basket basket = new Basket("Ana", new ArrayList<>(List.of("book")));
        Basket shallow = basket.shallowClone();
        Basket deep = basket.deepClone();
        Basket copied = new Basket(basket);
        basket.items.add("pen");                              // mutate the original's list
        check("shallow clone SHARES the list", shallow.items, List.of("book", "pen"));
        check("deep clone is independent", deep.items, List.of("book"));
        check("copy constructor is independent", copied.items, List.of("book"));
        check("clone without Cloneable throws",
                throwsCheckedName(() -> new NotCloneable().tryClone()), "CloneNotSupportedException");
    }

    static final class NotCloneable {
        Object tryClone() throws CloneNotSupportedException { return super.clone(); }
    }

    // =========================================================================
    // 9. JIT COMPILATION, TUNING AND DIAGNOSTICS (CHEAT SHEET)
    // =========================================================================
    //
    // EXECUTION: bytecode starts out INTERPRETED. Hot methods are compiled by
    // the JIT using TIERED COMPILATION:
    //   tier 0 interpreter -> tiers 1-3 C1 (fast to compile, gathers profiles)
    //   -> tier 4 C2 (slow to compile, heavily optimized from those profiles)
    // Key optimizations:
    //   inlining           small/hot callees are copied into the caller (the
    //                      biggest win; it enables all the others)
    //   escape analysis    objects that never escape: scalar replacement, and
    //                      locks removed (lock elision)
    //   loop opts          unrolling, range-check elimination, vectorization
    //   devirtualization   if only one implementation has been seen, call it
    //                      directly
    //   OSR                On-Stack Replacement: switch a long-RUNNING loop to
    //                      compiled code mid-loop (that's how section 1's
    //                      hoisting kicked in)
    //   deoptimization     if an assumption breaks (say a new subclass is
    //                      loaded), drop back to the interpreter and recompile
    // This is why microbenchmarks need WARM-UP and a harness like JMH.
    //
    // COMMON FLAGS
    //   -Xms / -Xmx                  initial / max heap (set them equal in
    //                                servers to avoid resizing)
    //   -Xss                         thread stack size
    //   -XX:MaxRAMPercentage=75      heap as a % of container memory
    //   -XX:MaxMetaspaceSize         cap class metadata
    //   -XX:MaxDirectMemorySize      cap off-heap NIO buffers
    //   -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...
    //   -Xlog:gc*:file=gc.log        unified GC logging (JDK 9+)
    //   -XX:MaxGCPauseMillis=200     pause-time goal (G1)
    //
    // DIAGNOSTIC TOOLS
    //   jcmd <pid> help               the swiss-army knife (GC.heap_info,
    //                                 Thread.print, GC.class_histogram, JFR.start...)
    //   jstack <pid>                  thread dump; deadlocks are listed at the end
    //   jmap -dump:live,format=b,file=heap.hprof <pid>   heap dump
    //   jstat -gcutil <pid> 1000      live GC statistics
    //   Java Flight Recorder (JFR) + JDK Mission Control: low-overhead profiling
    //   Eclipse MAT / VisualVM        analyze heap dumps, find leak suspects
    // =========================================================================

    static void section9_jitAndTuning() {
        section("9. JVM runtime info (see the cheat sheet in the comments)");

        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        System.out.println("      VM:        " + runtime.getVmName() + " " + runtime.getVmVersion());
        System.out.println("      JIT:       " + ManagementFactory.getCompilationMXBean().getName());
        System.out.println("      CPUs:      " + Runtime.getRuntime().availableProcessors());
        System.out.println("      JVM args:  " + runtime.getInputArguments().stream()
                .filter(a -> !a.startsWith("-Djavax") && !a.startsWith("-Dhttp") && !a.startsWith("-Djdk.http"))
                .toList());
        System.out.println("      uptime:    " + runtime.getUptime() + " ms");
        check("JIT compiler present", ManagementFactory.getCompilationMXBean() != null, true);
        check("threads visible via MXBean", ManagementFactory.getThreadMXBean().getThreadCount() > 0, true);
    }

    // =========================================================================
    // CHILD-JVM LAB: a tiny program compiled at startup and run with special flags
    // =========================================================================

    static final String GC_LAB_SOURCE = """
            import java.lang.ref.SoftReference;
            import java.nio.ByteBuffer;
            import java.util.ArrayList;
            import java.util.List;

            public class GcLab {
                static boolean stop;     // deliberately NOT volatile (hoisting demo)

                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "churn" -> {
                            byte[] keep = null;
                            for (int i = 0; i < 3_000_000; i++) keep = new byte[256];   // ~768 MB of garbage
                            System.out.println("CHURN_DONE " + keep.length);
                        }
                        case "soft" -> {
                            List<SoftReference<byte[]>> cache = new ArrayList<>();
                            for (int i = 0; i < 200; i++) cache.add(new SoftReference<>(new byte[1 << 20]));
                            long alive = cache.stream().filter(r -> r.get() != null).count();
                            System.out.println("SOFT_ALIVE=" + alive);
                            System.out.println("SOFT_OK");
                        }
                        case "heap-oom" -> {
                            List<byte[]> hog = new ArrayList<>();
                            try {
                                while (true) hog.add(new byte[1 << 20]);
                            } catch (OutOfMemoryError e) {
                                int mb = hog.size();
                                hog = null;                       // free memory so we can print
                                System.out.println("OOM_AFTER_MB=" + mb + " MSG=" + e.getMessage());
                            }
                        }
                        case "direct-oom" -> {
                            try {
                                ByteBuffer.allocateDirect(64 << 20);
                                System.out.println("DIRECT_OOM=none");
                            } catch (OutOfMemoryError e) {
                                System.out.println("DIRECT_OOM=" + e.getMessage());
                            }
                        }
                        case "hoist" -> {
                            Thread t = new Thread(() -> {
                                long n = 0;
                                while (!stop) n++;               // hot loop reading a plain field
                            });
                            t.setDaemon(true);
                            t.start();
                            Thread.sleep(500);                   // let the JIT compile the loop (OSR)
                            stop = true;
                            t.join(1_000);
                            System.out.println("HOIST_STILL_RUNNING=" + t.isAlive());
                            System.exit(0);
                        }
                        default -> throw new IllegalArgumentException(args[0]);
                    }
                }
            }
            """;

    static Path compileGcLab() throws IOException {
        Path dir = Files.createTempDirectory("gclab");
        Path src = dir.resolve("GcLab.java");
        Files.writeString(src, GC_LAB_SOURCE, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK (with javac) is required");
        int rc = compiler.run(null, null, null, "-d", dir.toString(), src.toString());
        if (rc != 0) throw new IllegalStateException("GcLab failed to compile");
        dir.toFile().deleteOnExit();
        return dir;
    }

    static String runLab(Path labDir, List<String> jvmArgs, String mode) throws Exception {
        List<String> cmd = new ArrayList<>(jvmArgs);
        cmd.addAll(List.of("-cp", labDir.toString(), "GcLab", mode));
        return runJvm(cmd);
    }

    /** Starts a child JVM from the SAME Java installation; returns stdout + stderr. */
    static String runJvm(List<String> args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS");      // keep child output clean
        pb.environment().remove("JDK_JAVA_OPTIONS");
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("child JVM timed out: " + cmd);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    static String extract(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        if (!m.find()) throw new AssertionError("pattern " + regex + " not found in:\n" + text);
        return m.group(1).trim();
    }

    // =========================================================================
    // TEST HELPERS
    // =========================================================================
    private static int passed = 0;

    static <T> void check(String label, T actual, T expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(label + "\n   expected: " + expected + "\n   actual:   " + actual);
        }
        passed++;
        System.out.printf("   ok  %-46s %s%n", label, actual);
    }

    static String throwsName(Runnable action) {
        try {
            action.run();
            return "no exception";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    @FunctionalInterface
    interface ThrowingAction { void run() throws Exception; }

    static String throwsCheckedName(ThrowingAction action) {
        try {
            action.run();
            return "no exception";
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    static void section(String title) {
        System.out.printf("%n== %s ==%n", title);
    }
}