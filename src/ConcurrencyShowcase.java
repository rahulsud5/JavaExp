import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * =============================================================================
 *  JAVA CONCURRENCY SHOWCASE: a banking / order-processing domain
 * =============================================================================
 *
 *  A guided tour of Java concurrency, from raw threads to modern APIs. Every
 *  section is runnable, explained in comments, and checks its own results.
 *
 *    1. Threads            creating, start vs run, lifecycle states,
 *                          interruption, daemon threads, uncaught exceptions
 *    2. Race conditions    lost updates: synchronized vs AtomicInteger vs
 *                          LongAdder
 *    3. Visibility         volatile and the Java Memory Model (happens-before)
 *    4. wait / notify      a classic bounded buffer with intrinsic locks
 *    5. Lock + Condition   ReentrantLock, multiple conditions, tryLock
 *    6. Deadlock           create one, DETECT it with ThreadMXBean, then
 *                          prevent it with lock ordering (bank transfers)
 *    7. Read-mostly data   ReadWriteLock cache, StampedLock optimistic reads
 *    8. Executors          thread pools, Callable/Future, invokeAll/Any,
 *                          cancellation, timeouts, custom ThreadPoolExecutor,
 *                          rejection policies, proper shutdown
 *    9. Scheduling         ScheduledExecutorService
 *   10. CompletableFuture  async pipelines: compose, combine, allOf, error
 *                          handling, timeouts
 *   11. Synchronizers      CountDownLatch, CyclicBarrier, Semaphore, Phaser,
 *                          Exchanger
 *   12. Concurrent         ConcurrentHashMap, CopyOnWriteArrayList,
 *       collections        BlockingQueue (poison pill), ConcurrentSkipListMap
 *   13. Fork/Join          divide and conquer with work stealing
 *   14. ThreadLocal        per-thread state, and the thread-pool LEAK pitfall
 *   15. Atomics and CAS    compare-and-set loops, AtomicReference, the ABA
 *                          problem
 *   16. Safe publication   double-checked locking, holder idiom, enum
 *                          singleton
 *   17. Virtual threads    Java 21 lightweight threads
 *
 *  RUN (JDK 21+):   java ConcurrencyShowcase.java
 *  Takes a few seconds; ends with "All N checks passed."
 *
 *  KEY VOCABULARY
 *    Atomicity   an operation happens all at once or not at all. count++
 *                is NOT atomic: it reads, adds, then writes.
 *    Visibility  a write by one thread is SEEN by another. Without
 *                synchronization, CPU caches and compiler reordering can
 *                hide writes indefinitely.
 *    Ordering    the order other threads observe operations in. The JVM and
 *                CPU reorder instructions unless a "happens-before"
 *                relationship forbids it.
 *    Happens-before (JMM): if action A happens-before B, then B sees A's
 *                effects. It's established by: unlocking a monitor -> later
 *                locking it; writing a volatile -> later reading it;
 *                Thread.start() -> anything in that thread; everything in a
 *                thread -> another thread's join() returning; putting into a
 *                concurrent collection -> taking it out; Future completion
 *                -> get() returning.
 * =============================================================================
 */
public class ConcurrencyShowcase {

    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        section1_threadBasics();
        section2_raceConditions();
        section3_visibility();
        section4_waitNotify();
        section5_locksAndConditions();
        section6_deadlock();
        section7_readMostly();
        section8_executors();
        section9_scheduling();
        section10_completableFuture();
        section11_synchronizers();
        section12_concurrentCollections();
        section13_forkJoin();
        section14_threadLocal();
        section15_atomicsAndCas();
        section16_safePublication();
        section17_virtualThreads();
        System.out.printf("%nAll %d checks passed in %d ms.%n", passed,
                Duration.ofNanos(System.nanoTime() - start).toMillis());
    }

    // =========================================================================
    // 1. THREAD BASICS
    // =========================================================================
    //
    // Thread lifecycle (Thread.State):
    //   NEW -> RUNNABLE -> (BLOCKED | WAITING | TIMED_WAITING)* -> TERMINATED
    //     BLOCKED        waiting to ENTER a synchronized block
    //     WAITING        wait(), join(), LockSupport.park() with no timeout
    //     TIMED_WAITING  sleep(ms), wait(ms), join(ms)...
    //
    // INTERRUPTION is Java's COOPERATIVE cancellation. interrupt() only sets a
    // flag; blocking methods (sleep, wait, join, BlockingQueue.take...) react
    // by throwing InterruptedException AND CLEARING the flag. The golden rule:
    // never swallow it. Either rethrow, or restore the flag with
    // Thread.currentThread().interrupt() so code further up can see it.
    // =========================================================================

    static void section1_threadBasics() throws Exception {
        section("1. Thread basics: creation, states, interruption");

        List<String> log = Collections.synchronizedList(new ArrayList<>());

        // Way 1: subclass Thread (rarely the best choice: it uses up your single inheritance)
        class AuditThread extends Thread {
            AuditThread() { super("audit"); }
            @Override public void run() { log.add("subclass ran on " + getName()); }
        }
        // Way 2: pass a Runnable (here a lambda). This separates the TASK from the THREAD.
        Thread worker = new Thread(() -> log.add("runnable ran on " + Thread.currentThread().getName()), "worker");

        Thread audit = new AuditThread();
        check("state before start()", worker.getState(), Thread.State.NEW);
        audit.start();                     // start() creates a NEW thread that calls run()
        worker.start();
        audit.join();                      // join() waits for the thread to finish
        worker.join();                     // (and creates a happens-before: we see its writes)
        check("state after join()", worker.getState(), Thread.State.TERMINATED);
        check("both threads ran", new TreeSet<>(log), new TreeSet<>(List.of("runnable ran on worker", "subclass ran on audit")));

        // COMMON BUG: calling run() directly runs on the CURRENT thread. No new thread!
        AtomicReference<String> ranOn = new AtomicReference<>();
        Thread notStarted = new Thread(() -> ranOn.set(Thread.currentThread().getName()), "never-used");
        notStarted.run();
        check("run() executes on the caller", ranOn.get(), Thread.currentThread().getName());

        // INTERRUPTING a sleeping thread
        AtomicBoolean gotInterrupted = new AtomicBoolean();
        AtomicBoolean flagRestored = new AtomicBoolean();
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(10_000);                      // would sleep 10 s...
            } catch (InterruptedException e) {
                gotInterrupted.set(true);
                Thread.currentThread().interrupt();       // RESTORE the flag (it was cleared)
                flagRestored.set(Thread.currentThread().isInterrupted());
            }
        }, "sleeper");
        sleeper.start();
        awaitState(sleeper, Thread.State.TIMED_WAITING);
        check("sleeping thread state", sleeper.getState(), Thread.State.TIMED_WAITING);
        sleeper.interrupt();                               // ...but we cancel it right away
        sleeper.join();
        check("InterruptedException received", gotInterrupted.get(), true);
        check("interrupt flag restored", flagRestored.get(), true);

        // DAEMON threads don't keep the JVM alive (good for background housekeeping)
        Thread daemon = new Thread(() -> {});
        daemon.setDaemon(true);                            // must be set BEFORE start()
        check("daemon flag", daemon.isDaemon(), true);

        // UNCAUGHT EXCEPTIONS kill only their own thread. Install a handler to see them.
        AtomicReference<String> caught = new AtomicReference<>();
        Thread failing = new Thread(() -> { throw new IllegalStateException("payment gateway down"); });
        failing.setUncaughtExceptionHandler((t, e) -> caught.set(e.getMessage()));
        failing.start();
        failing.join();
        check("uncaught exception handler", caught.get(), "payment gateway down");
    }

    // =========================================================================
    // 2. RACE CONDITIONS AND ATOMICITY
    // =========================================================================
    //
    // count++ compiles to three steps: READ count, ADD 1, WRITE count. Two
    // threads can both read 5 and both write 6, and one increment is LOST.
    // Fixes, from heaviest to lightest:
    //   synchronized   mutual exclusion; also guarantees visibility
    //   AtomicInteger  lock-free compare-and-set (CAS) in hardware
    //   LongAdder      spreads updates over several cells; fastest under HIGH
    //                  contention, but sum() is only a snapshot (use it for
    //                  statistics and metrics)
    // =========================================================================

    static final class UnsafeCounter {
        int count;
        void increment() { count++; }                    // read-modify-write: NOT atomic
    }

    static final class SynchronizedCounter {
        private int count;                               // guarded by `this`
        synchronized void increment() { count++; }
        synchronized int get() { return count; }         // reads must synchronize too (visibility!)
    }

    static void section2_raceConditions() throws Exception {
        section("2. Race conditions: lost updates and the fixes");

        int threads = 8, perThread = 100_000, expected = threads * perThread;

        var unsafe = new UnsafeCounter();
        var sync = new SynchronizedCounter();
        var atomic = new AtomicInteger();
        var adder = new LongAdder();

        runConcurrently(threads, id -> {
            for (int i = 0; i < perThread; i++) {
                unsafe.increment();
                sync.increment();
                atomic.incrementAndGet();
                adder.increment();
            }
        });

        System.out.printf("      unsafe counter: %,d of %,d (%,d updates lost)%n",
                unsafe.count, expected, expected - unsafe.count);
        check("unsafe never exceeds expected", unsafe.count <= expected, true);
        check("synchronized counter is exact", sync.get(), expected);
        check("AtomicInteger is exact", atomic.get(), expected);
        check("LongAdder is exact (when quiescent)", adder.sum(), (long) expected);
    }

    // =========================================================================
    // 3. VISIBILITY: volatile
    // =========================================================================
    //
    // Without synchronization, the JIT may hoist `while (running)` into
    // `if (running) while (true)`, because nothing in the loop changes running.
    // The worker then spins FOREVER even after another thread sets running =
    // false.
    //
    // `volatile` guarantees that every read sees the latest write, and it
    // forbids that reordering. It does NOT make compound actions atomic:
    // volatile count++ is still a race. Use volatile for flags and for
    // publishing immutable objects; use locks or atomics for read-modify-write.
    // =========================================================================

    static final class StoppableWorker implements Runnable {
        private volatile boolean running = true;     // remove `volatile` and this may never stop
        private long iterations;

        @Override public void run() {
            while (running) iterations++;
        }

        void stop() { running = false; }
        long iterations() { return iterations; }   // read after join(): join gives happens-before
    }

    static void section3_visibility() throws Exception {
        section("3. Visibility: volatile stop flag");

        var job = new StoppableWorker();
        Thread t = new Thread(job, "spinner");
        t.start();
        Thread.sleep(20);
        job.stop();
        t.join(2_000);
        check("volatile flag stopped the worker", t.isAlive(), false);
        check("worker made progress", job.iterations() > 0, true);
    }

    // =========================================================================
    // 4. wait / notify: THE CLASSIC BOUNDED BUFFER
    // =========================================================================
    //
    // Every object has an intrinsic lock (monitor) and a WAIT SET.
    //   wait()      releases the lock and sleeps until notified (must hold it)
    //   notify()    wakes ONE waiting thread
    //   notifyAll() wakes ALL of them (safer when threads wait for DIFFERENT
    //               conditions, like "not full" vs "not empty" here)
    //
    // ALWAYS call wait() in a WHILE loop, never an if:
    //   - SPURIOUS WAKEUPS: a thread may wake up with no notify at all
    //   - another thread may have taken the slot before this one reacquired
    //     the lock
    // In new code, prefer java.util.concurrent (BlockingQueue). This is shown
    // because it's foundational, and a classic interview topic.
    // =========================================================================

    static final class MonitorBoundedBuffer<T> {
        private final Deque<T> items = new ArrayDeque<>();
        private final int capacity;

        MonitorBoundedBuffer(int capacity) { this.capacity = capacity; }

        synchronized void put(T item) throws InterruptedException {
            while (items.size() == capacity) wait();     // WHILE, not if
            items.addLast(item);
            notifyAll();                                 // wake consumers waiting on "not empty"
        }

        synchronized T take() throws InterruptedException {
            while (items.isEmpty()) wait();
            T item = items.removeFirst();
            notifyAll();                                 // wake producers waiting on "not full"
            return item;
        }
    }

    static void section4_waitNotify() throws Exception {
        section("4. wait/notify: producer-consumer bounded buffer");

        var buffer = new MonitorBoundedBuffer<Integer>(5);
        var consumed = new LongAdder();
        int producers = 2, consumers = 2, perProducer = 1_000;

        runConcurrently(producers + consumers, id -> {
            if (id < producers) {
                for (int i = 1; i <= perProducer; i++) buffer.put(i);
            } else {
                for (int i = 0; i < perProducer; i++) consumed.add(buffer.take());
            }
        });
        long expected = (long) producers * perProducer * (perProducer + 1) / 2;
        check("every item consumed exactly once", consumed.sum(), expected);
    }

    // =========================================================================
    // 5. EXPLICIT LOCKS: ReentrantLock + Condition
    // =========================================================================
    //
    // ReentrantLock does everything `synchronized` does, plus:
    //   tryLock() / tryLock(timeout)  don't block forever (deadlock avoidance)
    //   lockInterruptibly()           waiting for the lock can be cancelled
    //   new ReentrantLock(true)       FAIR mode: FIFO handoff, lower throughput
    //   multiple Conditions           separate wait sets ("notFull",
    //                                 "notEmpty"), so signal() wakes exactly
    //                                 the right kind of thread
    //
    // ALWAYS unlock in a finally block. Unlike synchronized, nothing releases
    // a Lock automatically if an exception is thrown.
    // "Reentrant" means the thread that holds the lock may acquire it again
    // (a hold count tracks nesting). synchronized is reentrant too.
    // =========================================================================

    static final class LockBoundedBuffer<T> {
        private final Object[] items;
        private int head, tail, count;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();
        private final Condition notEmpty = lock.newCondition();

        LockBoundedBuffer(int capacity) { items = new Object[capacity]; }

        void put(T item) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (count == items.length) notFull.await();
                items[tail] = item;
                tail = (tail + 1) % items.length;           // circular array
                count++;
                notEmpty.signal();                          // wake exactly one CONSUMER
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        T take() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (count == 0) notEmpty.await();
                T item = (T) items[head];
                items[head] = null;                         // help GC
                head = (head + 1) % items.length;
                count--;
                notFull.signal();                           // wake exactly one PRODUCER
                return item;
            } finally {
                lock.unlock();
            }
        }
    }

    static void section5_locksAndConditions() throws Exception {
        section("5. ReentrantLock + Condition, tryLock, reentrancy");

        var buffer = new LockBoundedBuffer<Integer>(4);
        var consumed = new LongAdder();
        runConcurrently(6, id -> {
            if (id < 3) for (int i = 1; i <= 500; i++) buffer.put(i);
            else for (int i = 0; i < 500; i++) consumed.add(buffer.take());
        });
        check("Condition-based buffer is exact", consumed.sum(), 3L * 500 * 501 / 2);

        // tryLock with a timeout: give up instead of waiting forever
        ReentrantLock accountLock = new ReentrantLock();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            accountLock.lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                accountLock.unlock();
            }
        });
        holder.start();
        held.await();
        boolean acquired = accountLock.tryLock(50, TimeUnit.MILLISECONDS);
        check("tryLock times out while lock is held", acquired, false);
        release.countDown();
        holder.join();
        acquired = accountLock.tryLock(50, TimeUnit.MILLISECONDS);
        check("tryLock succeeds once released", acquired, true);

        // Reentrancy: the owner can lock again; the hold count tracks nesting
        accountLock.lock();
        check("reentrant hold count", accountLock.getHoldCount(), 2);
        accountLock.unlock();
        accountLock.unlock();
        check("fully released", accountLock.isLocked(), false);
    }

    // =========================================================================
    // 6. DEADLOCK: CREATE IT, DETECT IT, PREVENT IT
    // =========================================================================
    //
    // A deadlock needs all four Coffman conditions:
    //   1. mutual exclusion   2. hold and wait   3. no preemption
    //   4. CIRCULAR WAIT: T1 holds A and wants B; T2 holds B and wants A
    // Break any one and deadlock is impossible. The practical fix is to break
    // #4 with a global LOCK ORDER: always lock the lower account id first.
    // Alternatives: tryLock with a timeout and back-off, or a single
    // coarser lock.
    // =========================================================================

    static final class Account {
        final int id;
        private long balance;                          // guarded by lock
        final ReentrantLock lock = new ReentrantLock();

        Account(int id, long balance) { this.id = id; this.balance = balance; }

        long balance() {
            lock.lock();
            try { return balance; } finally { lock.unlock(); }
        }
    }

    public final class AtmMac{

    }

    /** Deadlock-free transfer: locks are ALWAYS taken in ascending id order. */
    static boolean transfer(Account from, Account to, long amount) {
        if (from == to) return false;
        Account first = from.id < to.id ? from : to;
        Account second = from.id < to.id ? to : from;
        first.lock.lock();
        try {
            second.lock.lock();
            try {
                if (from.balance < amount) return false;     // check-then-act is safe: both locks held
                from.balance -= amount;
                to.balance += amount;
                return true;
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    static void section6_deadlock() throws Exception {
        section("6. Deadlock: create, detect with ThreadMXBean, prevent");

        // --- CREATE a real deadlock (daemon threads, so the JVM can still exit)
        Object lockA = new Object(), lockB = new Object();
        CountDownLatch bothHoldFirstLock = new CountDownLatch(2);
        Thread t1 = new Thread(() -> {
            synchronized (lockA) {
                bothHoldFirstLock.countDown();
                awaitQuietly(bothHoldFirstLock);
                synchronized (lockB) { /* never reached */ }
            }
        }, "transfer-A-to-B");
        Thread t2 = new Thread(() -> {
            synchronized (lockB) {
                bothHoldFirstLock.countDown();
                awaitQuietly(bothHoldFirstLock);
                synchronized (lockA) { /* never reached */ }
            }
        }, "transfer-B-to-A");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        // --- DETECT it. This is what monitoring tools and `jstack` report.
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = null;
        for (int i = 0; i < 200 && deadlocked == null; i++) {
            deadlocked = mx.findDeadlockedThreads();
            if (deadlocked == null) Thread.sleep(10);
        }
        Set<String> names = new TreeSet<>();
        for (ThreadInfo info : mx.getThreadInfo(Objects.requireNonNull(deadlocked))) names.add(info.getThreadName());
        check("deadlock detected", names, new TreeSet<>(Set.of("transfer-A-to-B", "transfer-B-to-A")));
        check("deadlocked thread is BLOCKED", t1.getState(), Thread.State.BLOCKED);
        // (These two threads stay stuck forever. That's why they are daemons.)

        // --- PREVENT it: lock ordering. 8 threads x 20,000 random transfers in
        // BOTH directions between 5 accounts. No deadlock, and no money is
        // created or destroyed.
        List<Account> accounts = IntStream.range(0, 5).mapToObj(i -> new Account(i, 1_000)).toList();
        runConcurrently(8, id -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();   // per-thread RNG, no contention
            for (int i = 0; i < 20_000; i++) {
                transfer(accounts.get(rnd.nextInt(5)), accounts.get(rnd.nextInt(5)), rnd.nextInt(1, 50));
            }
        });
        long total = accounts.stream().mapToLong(Account::balance).sum();
        check("lock ordering: money is conserved", total, 5_000L);
    }

    // =========================================================================
    // 7. READ-MOSTLY DATA: ReadWriteLock and StampedLock
    // =========================================================================
    //
    // ReentrantReadWriteLock: many readers at once OR one writer. It helps when
    // reads vastly outnumber writes and read operations aren't trivial.
    //
    // StampedLock (Java 8): adds OPTIMISTIC READS. Read with no lock at all,
    // then validate(stamp). If a write happened meanwhile, retry under a real
    // read lock. It's very fast for tiny read sections, but it's NOT reentrant
    // and has no Conditions.
    // =========================================================================

    static final class ExchangeRateCache {
        private final Map<String, Double> rates = new HashMap<>();   // guarded by rw
        private final ReadWriteLock rw = new ReentrantReadWriteLock();
        private final AtomicInteger loads = new AtomicInteger();

        double rate(String currency) {
            rw.readLock().lock();                       // many threads may hold this together
            try {
                Double cached = rates.get(currency);
                if (cached != null) return cached;
            } finally {
                rw.readLock().unlock();
            }
            // A read lock CANNOT be upgraded to a write lock (that would deadlock),
            // so release it, take the write lock, and CHECK AGAIN: another thread
            // may have loaded the value in between.
            rw.writeLock().lock();
            try {
                return rates.computeIfAbsent(currency, c -> {
                    loads.incrementAndGet();
                    return slowLookup(c);
                });
            } finally {
                rw.writeLock().unlock();
            }
        }

        private static double slowLookup(String currency) {
            sleepQuietly(5);
            return switch (currency) {
                case "EUR" -> 0.92;
                case "GBP" -> 0.79;
                default -> 1.0;
            };
        }
    }

    /** A point whose invariant is x == y. Writers move both coordinates together. */
    static final class DiagonalPoint {
        private double x, y;
        private final StampedLock sl = new StampedLock();

        void move(double delta) {
            long stamp = sl.writeLock();
            try {
                x += delta;
                y += delta;
            } finally {
                sl.unlockWrite(stamp);
            }
        }

        /** Returns true if the reader observed a CONSISTENT snapshot (x == y). */
        boolean readConsistent() {
            long stamp = sl.tryOptimisticRead();       // no locking at all
            double cx = x, cy = y;                     // may see a half-finished write...
            if (!sl.validate(stamp)) {                 // ...so check whether a write happened
                stamp = sl.readLock();                 // fall back to a real read lock
                try {
                    cx = x;
                    cy = y;
                } finally {
                    sl.unlockRead(stamp);
                }
            }
            return cx == cy;
        }
    }

    static void section7_readMostly() throws Exception {
        section("7. ReadWriteLock cache + StampedLock optimistic reads");

        var cache = new ExchangeRateCache();
        var sum = new DoubleAdder();
        runConcurrently(16, id -> {
            for (int i = 0; i < 200; i++) sum.add(cache.rate(i % 2 == 0 ? "EUR" : "GBP"));
        });
        check("each rate loaded exactly once", cache.loads.get(), 2);
        check("all reads correct", Math.round(sum.sum() * 100) / 100.0, Math.round(16 * 100 * (0.92 + 0.79) * 100) / 100.0);

        var point = new DiagonalPoint();
        var inconsistent = new AtomicInteger();
        runConcurrently(6, id -> {
            for (int i = 0; i < 50_000; i++) {
                if (id < 2) point.move(1);
                else if (!point.readConsistent()) inconsistent.incrementAndGet();
            }
        });
        check("optimistic reads never saw a torn write", inconsistent.get(), 0);
    }

    // =========================================================================
    // 8. EXECUTORS: THREAD POOLS AND FUTURES
    // =========================================================================
    //
    // Creating a thread per task is expensive (at least for platform threads).
    // An ExecutorService reuses a pool of threads and separates WHAT to run
    // (Runnable/Callable) from HOW it runs.
    //
    // Factory methods on Executors:
    //   newFixedThreadPool(n)            n threads, UNBOUNDED queue (can run out of memory!)
    //   newCachedThreadPool()            grows without limit (dangerous under load)
    //   newSingleThreadExecutor()        sequential, in-order execution
    //   newScheduledThreadPool(n)        delays and periodic tasks
    //   newVirtualThreadPerTaskExecutor  Java 21: one virtual thread per task
    // In production, prefer a ThreadPoolExecutor configured explicitly with a
    // BOUNDED queue and a deliberate rejection policy (shown below).
    //
    // ALWAYS shut pools down. Their non-daemon threads keep the JVM alive.
    // =========================================================================

    static void section8_executors() throws Exception {
        section("8. Executors: Callable/Future, invokeAll/Any, cancel, custom pools");

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            // Callable returns a value (and may throw); Future is its pending result
            Future<Integer> answer = pool.submit(() -> 6 * 7);
            check("Future.get()", answer.get(), 42);

            // invokeAll: run all tasks, results come back in TASK order
            List<Callable<String>> fraudChecks = List.of(
                    () -> { sleepQuietly(30); return "order-1 ok"; },
                    () -> { sleepQuietly(10); return "order-2 ok"; },
                    () -> "order-3 ok");
            List<String> results = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(fraudChecks)) results.add(f.get());
            check("invokeAll keeps task order", results, List.of("order-1 ok", "order-2 ok", "order-3 ok"));

            // invokeAny: first SUCCESSFUL result wins, the others are cancelled.
            // Typical use: query redundant replicas and take the fastest answer.
            String fastest = pool.invokeAny(List.of(
                    () -> { throw new IllegalStateException("replica-1 down"); },
                    () -> { sleepQuietly(500); return "replica-2"; },
                    () -> { sleepQuietly(20); return "replica-3"; }));
            check("invokeAny returns first success", fastest, "replica-3");

            // An exception inside a task is WRAPPED in ExecutionException
            Future<Object> failing = pool.submit(() -> { throw new IllegalArgumentException("bad card"); });
            String cause;
            try {
                failing.get();
                cause = "none";
            } catch (ExecutionException e) {
                cause = e.getCause().getMessage();
            }
            check("ExecutionException wraps the cause", cause, "bad card");

            // Timeouts and cancellation
            Future<String> slowReport = pool.submit(() -> { Thread.sleep(5_000); return "report"; });
            String outcome;
            try {
                outcome = slowReport.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                outcome = "timed out";
                slowReport.cancel(true);          // true = interrupt the running task
            }
            check("Future.get(timeout)", outcome, "timed out");
            check("cancelled future", slowReport.isCancelled(), true);
            check("get() on cancelled throws",
                    throwsName(() -> slowReport.get()), "CancellationException");
        } finally {
            shutdownAndAwait(pool);
        }

        // ---- A CUSTOM ThreadPoolExecutor: every knob made explicit
        //   core=1, max=1 threads; queue capacity 1; named threads; AbortPolicy
        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory namedFactory = runnable -> {
            Thread t = new Thread(runnable, "billing-" + threadCounter.incrementAndGet());
            t.setUncaughtExceptionHandler((th, ex) -> System.err.println(th.getName() + " died: " + ex));
            return t;
        };

        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> workerName = new AtomicReference<>();
        ThreadPoolExecutor billing = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),                 // BOUNDED: provides back-pressure
                namedFactory,
                new ThreadPoolExecutor.AbortPolicy());       // reject with an exception when full
        try {
            billing.execute(() -> { workerName.set(Thread.currentThread().getName()); awaitQuietly(release); }); // occupies the thread
            billing.execute(() -> {});                       // waits in the queue (capacity 1)
            String rejected = throwsName(() -> billing.execute(() -> {}));   // no thread, no queue room
            check("AbortPolicy rejects when saturated", rejected, "RejectedExecutionException");
        } finally {
            release.countDown();
            shutdownAndAwait(billing);
        }
        check("custom ThreadFactory names threads", workerName.get(), "billing-1");

        // CallerRunsPolicy: when saturated, the SUBMITTING thread runs the task
        // itself. That naturally slows producers down (back-pressure) and loses
        // nothing.
        CountDownLatch release2 = new CountDownLatch(1);
        AtomicReference<String> overflowRanOn = new AtomicReference<>();
        ThreadPoolExecutor reports = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            reports.execute(() -> awaitQuietly(release2));
            reports.execute(() -> {});
            reports.execute(() -> overflowRanOn.set(Thread.currentThread().getName()));   // runs right here
        } finally {
            release2.countDown();
            shutdownAndAwait(reports);
        }
        check("CallerRunsPolicy runs on the submitter", overflowRanOn.get(), Thread.currentThread().getName());
    }

    // =========================================================================
    // 9. SCHEDULING
    // =========================================================================
    //   schedule(task, delay)                   run once after a delay
    //   scheduleAtFixedRate(task, init, period) start times are fixed: t, t+p, t+2p...
    //   scheduleWithFixedDelay(...)             wait `delay` AFTER each run ends
    // If a periodic task throws, later runs are silently cancelled, so catch
    // exceptions inside periodic tasks.
    // =========================================================================

    static void section9_scheduling() throws Exception {
        section("9. ScheduledExecutorService");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        try {
            CountDownLatch heartbeats = new CountDownLatch(3);
            ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(heartbeats::countDown, 0, 10, TimeUnit.MILLISECONDS);
            check("3 heartbeats at a fixed rate", heartbeats.await(2, TimeUnit.SECONDS), true);
            heartbeat.cancel(false);

            ScheduledFuture<String> reminder = scheduler.schedule(() -> "invoice reminder sent", 20, TimeUnit.MILLISECONDS);
            check("one-shot delayed task", reminder.get(), "invoice reminder sent");
        } finally {
            shutdownAndAwait(scheduler);
        }
    }

    // =========================================================================
    // 10. CompletableFuture: ASYNC PIPELINES
    // =========================================================================
    //
    // A Future can only be blocked on. A CompletableFuture can be CHAINED:
    //   thenApply(fn)          transform the result            (like map)
    //   thenCompose(fn)        chain another async step        (like flatMap)
    //   thenCombine(other, fn) join two INDEPENDENT futures
    //   allOf / anyOf          wait for all, or for the first
    //   exceptionally(fn)      recover from failure
    //   handle(fn)             see the result OR the exception
    //   orTimeout / completeOnTimeout   (Java 9) built-in timeouts
    // Methods ending in ...Async run on a pool. Without an explicit executor
    // they use ForkJoinPool.commonPool(). For blocking I/O, PASS YOUR OWN
    // executor so the common pool (used by parallel streams) isn't starved.
    // =========================================================================

    record Customer(int id, String name) {}

    static Customer fetchCustomer(int id) { sleepQuietly(20); return new Customer(id, "Ana"); }
    static List<String> fetchOrders(Customer c) { sleepQuietly(20); return List.of("o-1", "o-2", "o-3"); }
    static int fetchLoyaltyDiscount(int customerId) { sleepQuietly(30); return 10; }
    static int quote(int item) { sleepQuietly(10 * item); return item * 100; }

    static void section10_completableFuture() throws Exception {
        section("10. CompletableFuture pipelines");

        ExecutorService io = Executors.newFixedThreadPool(8);
        try {
            // thenCompose (dependent async step) + thenCombine (independent step in parallel)
            CompletableFuture<Customer> customer = CompletableFuture.supplyAsync(() -> fetchCustomer(7), io);
            CompletableFuture<List<String>> orders = customer.thenComposeAsync(c -> CompletableFuture.supplyAsync(() -> fetchOrders(c), io), io);
            CompletableFuture<Integer> discount = CompletableFuture.supplyAsync(() -> fetchLoyaltyDiscount(7), io);

            String summary = orders
                    .thenCombine(discount, (o, d) -> o.size() + " orders, " + d + "% discount")
                    .thenApply(s -> customer.join().name() + ": " + s)   // join() is fine: customer is already done
                    .get(2, TimeUnit.SECONDS);
            check("compose + combine", summary, "Ana: 3 orders, 10% discount");

            // allOf: fan out 5 quotes in PARALLEL, then aggregate (about 50 ms, not 150 ms)
            List<CompletableFuture<Integer>> quotes = IntStream.rangeClosed(1, 5)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> quote(i), io))
                    .toList();
            int total = CompletableFuture.allOf(quotes.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> quotes.stream().mapToInt(CompletableFuture::join).sum())
                    .get(2, TimeUnit.SECONDS);
            check("allOf fan-out/fan-in", total, 1_500);

            // anyOf: the first one to finish wins
            Object first = CompletableFuture.anyOf(
                    CompletableFuture.supplyAsync(() -> { sleepQuietly(300); return "slow-mirror"; }, io),
                    CompletableFuture.supplyAsync(() -> { sleepQuietly(10); return "fast-mirror"; }, io)).get();
            check("anyOf", first, "fast-mirror");

            // exceptionally: recover with a fallback value
            String inventory = CompletableFuture.<String>supplyAsync(() -> { throw new IllegalStateException("inventory service down"); }, io)
                    .exceptionally(ex -> "fallback (" + rootCause(ex).getMessage() + ")")
                    .get();
            check("exceptionally -> fallback", inventory, "fallback (inventory service down)");

            // handle: one callback sees either outcome
            int itemsInCart = 0;
            String handled = CompletableFuture.supplyAsync(() -> 100 / itemsInCart, io)
                    .handle((value, ex) -> ex == null ? "value " + value : "error " + rootCause(ex).getClass().getSimpleName())
                    .get();
            check("handle sees the exception", handled, "error ArithmeticException");

            // Timeouts (Java 9+)
            String cached = CompletableFuture.supplyAsync(() -> { sleepQuietly(1_000); return "live price"; }, io)
                    .completeOnTimeout("cached price", 50, TimeUnit.MILLISECONDS)
                    .get();
            check("completeOnTimeout fallback", cached, "cached price");

            CompletableFuture<String> strict = CompletableFuture.supplyAsync(() -> { sleepQuietly(1_000); return "x"; }, io)
                    .orTimeout(50, TimeUnit.MILLISECONDS);
            String timeoutCause;
            try {
                strict.get();
                timeoutCause = "none";
            } catch (ExecutionException e) {
                timeoutCause = e.getCause().getClass().getSimpleName();
            }
            check("orTimeout fails the future", timeoutCause, "TimeoutException");

            // Completing a future MANUALLY: bridge a callback-style API into a future
            CompletableFuture<String> webhook = new CompletableFuture<>();
            io.submit(() -> { sleepQuietly(10); webhook.complete("payment confirmed"); });
            check("manual complete()", webhook.get(1, TimeUnit.SECONDS), "payment confirmed");
        } finally {
            io.shutdownNow();                        // interrupt the leftover sleeping tasks
            io.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    // =========================================================================
    // 11. SYNCHRONIZERS
    // =========================================================================
    //   CountDownLatch  one-shot gate: N events happen -> waiters proceed. Not reusable.
    //   CyclicBarrier   N threads wait for EACH OTHER, then all proceed. Reusable
    //                   (cyclic), with an optional barrier action per round.
    //   Semaphore       N permits: limits how many threads use a resource at once.
    //   Phaser          a flexible, reusable barrier; parties can register and
    //                   deregister dynamically.
    //   Exchanger       two threads swap objects at a meeting point.
    // =========================================================================

    static void section11_synchronizers() throws Exception {
        section("11. Synchronizers: latch, barrier, semaphore, phaser, exchanger");

        // --- CountDownLatch: a start gate (all begin together) + a finish line
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(5);
        AtomicInteger processed = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            new Thread(() -> {
                awaitQuietly(startGate);              // every worker blocks here...
                processed.incrementAndGet();
                finishLine.countDown();
            }).start();
        }
        check("nobody started before the gate", processed.get(), 0);
        startGate.countDown();                        // ...until this single release
        check("latch: all 5 finished", finishLine.await(2, TimeUnit.SECONDS) && processed.get() == 5, true);

        // --- CyclicBarrier: 3 branch offices settle accounts in 3 rounds.
        // The barrier action runs once per round, after ALL parties arrive.
        List<String> rounds = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger arrivedThisRound = new AtomicInteger();
        CyclicBarrier settlement = new CyclicBarrier(3, () ->
                rounds.add("round " + (rounds.size() + 1) + ": " + arrivedThisRound.getAndSet(0) + " offices"));
        runConcurrently(3, id -> {
            for (int round = 0; round < 3; round++) {
                sleepQuietly(ThreadLocalRandom.current().nextInt(5));
                arrivedThisRound.incrementAndGet();
                settlement.await();                   // the barrier resets itself for the next round
            }
        });
        check("barrier action ran once per round", rounds,
                List.of("round 1: 3 offices", "round 2: 3 offices", "round 3: 3 offices"));

        // --- Semaphore: at most 3 concurrent connections to a legacy mainframe
        Semaphore connections = new Semaphore(3);
        AtomicInteger inUse = new AtomicInteger(), maxInUse = new AtomicInteger();
        runConcurrently(12, id -> {
            connections.acquire();
            try {
                int now = inUse.incrementAndGet();
                maxInUse.accumulateAndGet(now, Math::max);
                sleepQuietly(10);
                inUse.decrementAndGet();
            } finally {
                connections.release();                // ALWAYS release in finally
            }
        });
        check("semaphore capped concurrency at 3", maxInUse.get() <= 3 && maxInUse.get() >= 1, true);

        // --- Phaser: a reusable barrier with dynamic membership.
        // onAdvance runs at the end of each phase; returning true terminates the phaser.
        List<String> phases = Collections.synchronizedList(new ArrayList<>());
        Phaser batch = new Phaser(1) {                // 1 = the main thread registers itself
            @Override protected boolean onAdvance(int phase, int registeredParties) {
                phases.add("phase " + phase + " parties=" + registeredParties);
                return registeredParties == 0;
            }
        };
        List<Thread> stages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            batch.register();                         // a party joins dynamically
            Thread t = new Thread(() -> {
                batch.arriveAndAwaitAdvance();        // phase 0: validate
                batch.arriveAndAwaitAdvance();        // phase 1: post
                batch.arriveAndDeregister();          // leave
            });
            stages.add(t);
            t.start();
        }
        batch.arriveAndDeregister();                  // main doesn't take part in the phases
        for (Thread t : stages) t.join();
        check("phaser phases", phases, List.of("phase 0 parties=3", "phase 1 parties=3", "phase 2 parties=0"));
        check("phaser terminated", batch.isTerminated(), true);

        // --- Exchanger: double buffering. The filler hands over a full buffer
        // and receives the empty one back.
        Exchanger<List<String>> exchanger = new Exchanger<>();
        AtomicReference<List<String>> drainerGot = new AtomicReference<>();
        Thread drainer = new Thread(() -> {
            try {
                drainerGot.set(exchanger.exchange(new ArrayList<>()));     // gives empty, gets full
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        drainer.start();
        List<String> back = exchanger.exchange(new ArrayList<>(List.of("tx1", "tx2")));  // gives full, gets empty
        drainer.join();
        check("exchanger swapped buffers", drainerGot.get() + " / " + back, "[tx1, tx2] / []");
    }

    // =========================================================================
    // 12. CONCURRENT COLLECTIONS
    // =========================================================================
    //   ConcurrentHashMap      lock striping / CAS per bin. Atomic compound ops:
    //                          merge, compute, computeIfAbsent, putIfAbsent.
    //                          Iterators are WEAKLY CONSISTENT: no
    //                          ConcurrentModificationException, though they may
    //                          miss concurrent updates. No null keys or values.
    //   CopyOnWriteArrayList   every write copies the array; iteration is
    //                          lock-free over a snapshot. Ideal for listener
    //                          lists (rare writes, frequent iteration).
    //   BlockingQueue          producer/consumer hand-off with back-pressure:
    //                          ArrayBlockingQueue (bounded),
    //                          LinkedBlockingQueue, PriorityBlockingQueue,
    //                          DelayQueue, SynchronousQueue (no capacity at all)
    //   ConcurrentSkipListMap  a sorted, concurrent NavigableMap
    //
    // PITFALL: `if (!map.containsKey(k)) map.put(k, v);` is a CHECK-THEN-ACT race
    // even on a ConcurrentHashMap. Use putIfAbsent / computeIfAbsent.
    // =========================================================================

    static void section12_concurrentCollections() throws Exception {
        section("12. Concurrent collections");

        // --- ConcurrentHashMap.merge: atomic per-key counting from many threads
        String[] events = {"login", "purchase", "login", "refund", "login", "purchase"};
        ConcurrentHashMap<String, Integer> eventCounts = new ConcurrentHashMap<>();
        runConcurrently(8, id -> {
            for (int i = 0; i < 1_000; i++) {
                for (String e : events) eventCounts.merge(e, 1, Integer::sum);   // atomic read-modify-write
            }
        });
        check("CHM merge counts are exact", new TreeMap<>(eventCounts),
                new TreeMap<>(Map.of("login", 24_000, "purchase", 16_000, "refund", 8_000)));
        check("CHM parallel bulk reduce", eventCounts.reduceValues(1, Integer::sum), 48_000);

        // computeIfAbsent runs the factory at most ONCE per key, even under contention
        ConcurrentHashMap<String, LongAdder> perCustomer = new ConcurrentHashMap<>();
        AtomicInteger adderCreations = new AtomicInteger();
        runConcurrently(8, id -> {
            for (int i = 0; i < 1_000; i++) {
                perCustomer.computeIfAbsent("cust-42", k -> { adderCreations.incrementAndGet(); return new LongAdder(); }).increment();
            }
        });
        check("computeIfAbsent created the value once", adderCreations.get(), 1);
        check("per-customer total", perCustomer.get("cust-42").sum(), 8_000L);

        // --- CopyOnWriteArrayList: modifying while iterating is SAFE (snapshot iteration)
        List<String> listeners = new CopyOnWriteArrayList<>(List.of("audit", "email"));
        int seen = 0;
        for (String l : listeners) {
            seen++;
            listeners.add(l + "-copy");          // would throw CME on an ArrayList
        }
        check("COW iterator sees the snapshot", seen, 2);
        check("COW list has the new items", listeners.size(), 4);

        // --- BlockingQueue + POISON PILL shutdown
        // Producers put() (blocking when full); consumers take() (blocking when
        // empty). To stop consumers cleanly, send one special "poison" item per
        // consumer.
        final int POISON = -1;
        BlockingQueue<Integer> payments = new ArrayBlockingQueue<>(10);
        LongAdder settled = new LongAdder();
        int consumers = 3;
        runConcurrently(consumers + 1, id -> {
            if (id == 0) {                                   // producer
                for (int i = 1; i <= 100; i++) payments.put(i);
                for (int c = 0; c < consumers; c++) payments.put(POISON);
            } else {                                         // consumers
                while (true) {
                    int p = payments.take();
                    if (p == POISON) break;
                    settled.add(p);
                }
            }
        });
        check("poison-pill pipeline processed all", settled.sum(), 5_050L);

        // offer/poll with timeouts never block forever
        BlockingQueue<String> tiny = new ArrayBlockingQueue<>(1);
        check("offer into empty queue", tiny.offer("a"), true);
        check("offer(timeout) into full queue", tiny.offer("b", 10, TimeUnit.MILLISECONDS), false);

        // --- ConcurrentSkipListMap: sorted AND concurrent
        ConcurrentSkipListMap<Integer, String> orderBook = new ConcurrentSkipListMap<>();
        runConcurrently(4, id -> {
            for (int i = 0; i < 250; i++) orderBook.put(id * 250 + i, "bid");
        });
        check("skip list size", orderBook.size(), 1_000);
        check("skip list stays sorted", orderBook.firstKey() + ".." + orderBook.lastKey(), "0..999");
        check("navigable: ceilingKey", orderBook.ceilingKey(500), 500);
    }

    // =========================================================================
    // 13. FORK/JOIN: DIVIDE AND CONQUER
    // =========================================================================
    //
    // Split a big task into halves until it's small enough, solve the pieces,
    // then combine. ForkJoinPool uses WORK STEALING: idle threads steal queued
    // subtasks from busy threads' deques, which balances load automatically.
    // Parallel streams run on ForkJoinPool.commonPool().
    //
    // Idiom: fork() one half, compute() the other half in THIS thread, then
    // join(). That keeps the current thread busy instead of just waiting.
    // Use it for CPU-bound work only. Never block (I/O) inside fork/join tasks.
    // =========================================================================

    static final class SumTask extends RecursiveTask<Long> {
        private static final long serialVersionUID = 1L;   // ForkJoinTask is Serializable
        private static final int THRESHOLD = 10_000;
        private final long[] data;
        private final int from, to;

        SumTask(long[] data, int from, int to) {
            this.data = data;
            this.from = from;
            this.to = to;
        }

        @Override
        protected Long compute() {
            if (to - from <= THRESHOLD) {                   // base case: small enough, so loop
                long sum = 0;
                for (int i = from; i < to; i++) sum += data[i];
                return sum;
            }
            int mid = (from + to) >>> 1;                    // >>> avoids int overflow
            SumTask left = new SumTask(data, from, mid);
            SumTask right = new SumTask(data, mid, to);
            left.fork();                                    // schedule left asynchronously
            long rightSum = right.compute();                // do right ourselves
            return left.join() + rightSum;                  // wait for left and combine
        }
    }

    static void section13_forkJoin() throws Exception {
        section("13. Fork/Join: parallel divide and conquer");

        long[] transactions = LongStream.rangeClosed(1, 2_000_000).toArray();
        try (ForkJoinPool pool = new ForkJoinPool(4)) {      // ForkJoinPool is AutoCloseable (Java 19+)
            long sum = pool.invoke(new SumTask(transactions, 0, transactions.length));
            check("fork/join sum", sum, 2_000_000L * 2_000_001L / 2);
            check("pool parallelism", pool.getParallelism(), 4);
            System.out.printf("      tasks stolen between workers: %,d%n", pool.getStealCount());
        }
        check("same via parallel stream (common pool)", LongStream.of(transactions).parallel().sum(), 2_000_000L * 2_000_001L / 2);
    }

    // =========================================================================
    // 14. ThreadLocal
    // =========================================================================
    //
    // Each thread gets its OWN copy of the variable. Typical uses: request or
    // trace context, transaction context, per-thread caches of objects that
    // aren't thread-safe (like SimpleDateFormat).
    //
    // POOL PITFALL: pool threads live forever, so a ThreadLocal set by one task
    // is still there for the NEXT task on that thread. That leaks data
    // between requests (a security issue) and memory. ALWAYS remove() in a
    // finally block.
    // (For virtual threads, Java 21 previewed ScopedValue as a safer, cheaper
    //  alternative.)
    // =========================================================================

    static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();
    static final ThreadLocal<StringBuilder> SCRATCH = ThreadLocal.withInitial(StringBuilder::new);

    static void section14_threadLocal() throws Exception {
        section("14. ThreadLocal: per-thread context and the pool leak");

        AtomicInteger mismatches = new AtomicInteger();
        runConcurrently(8, id -> {
            REQUEST_ID.set("req-" + id);
            for (int i = 0; i < 1_000; i++) {
                SCRATCH.get().setLength(0);
                SCRATCH.get().append("req-").append(id);         // a per-thread buffer: no sharing
                if (!REQUEST_ID.get().equals(SCRATCH.get().toString())) mismatches.incrementAndGet();
            }
            REQUEST_ID.remove();
        });
        check("each thread saw only its own value", mismatches.get(), 0);

        // The LEAK: a single-thread pool reuses the same thread for both tasks
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> REQUEST_ID.set("alice-session")).get();     // forgot to remove()!
            String leaked = pool.submit(() -> REQUEST_ID.get()).get();    // the next "request"
            check("LEAK: next task sees stale value", leaked, "alice-session");

            // The fix: try/finally remove()
            pool.submit(() -> {
                REQUEST_ID.set("bob-session");
                try {
                    /* handle the request */
                } finally {
                    REQUEST_ID.remove();
                }
            }).get();
            check("after remove(): clean", pool.submit(() -> REQUEST_ID.get()).get(), null);
        } finally {
            shutdownAndAwait(pool);
        }
    }

    // =========================================================================
    // 15. ATOMICS AND COMPARE-AND-SET (CAS)
    // =========================================================================
    //
    // CAS: "set the value to NEW only if it's still EXPECTED". The CPU does this
    // atomically. Lock-free algorithms loop: read, compute, then CAS; retry if
    // another thread won the race.
    //   updateAndGet / accumulateAndGet hide the loop for you. The function may
    //   run SEVERAL times under contention, so it must have NO side effects.
    //
    // AtomicReference + an IMMUTABLE object updates SEVERAL fields atomically,
    // with no locks: build a new object, then CAS the reference.
    //
    // ABA PROBLEM: the value goes A -> B -> A; a CAS expecting A succeeds even
    // though the state changed in between. AtomicStampedReference pairs the
    // value with a version stamp to detect this.
    // =========================================================================

    /** Immutable snapshot: count and total always change TOGETHER. */
    record PaymentStats(long count, long totalCents) {
        PaymentStats add(long cents) { return new PaymentStats(count + 1, totalCents + cents); }
    }

    static void section15_atomicsAndCas() throws Exception {
        section("15. Atomics, CAS loops and the ABA problem");

        // A hand-written CAS loop: track the largest payment
        AtomicLong largest = new AtomicLong(Long.MIN_VALUE);
        runConcurrently(8, id -> {
            for (int i = 0; i < 10_000; i++) {
                long candidate = (long) id * 10_000 + i;
                long current;
                do {
                    current = largest.get();
                    if (candidate <= current) break;                    // nothing to do
                } while (!largest.compareAndSet(current, candidate));   // lost the race? retry
            }
        });
        check("manual CAS max", largest.get(), 79_999L);

        // AtomicReference + immutable record: multi-field atomic updates
        AtomicReference<PaymentStats> stats = new AtomicReference<>(new PaymentStats(0, 0));
        runConcurrently(8, id -> {
            for (int i = 0; i < 5_000; i++) stats.updateAndGet(s -> s.add(250));   // pure function
        });
        check("count and total stay consistent", stats.get(), new PaymentStats(40_000, 10_000_000));

        // LongAccumulator: like LongAdder but for any associative function
        LongAccumulator maxLatency = new LongAccumulator(Math::max, 0);
        runConcurrently(4, id -> { for (int i = 0; i < 1_000; i++) maxLatency.accumulate(id * 1_000L + i); });
        check("LongAccumulator max", maxLatency.get(), 3_999L);

        // ABA demonstration (single-threaded for clarity)
        String a = "A", b = "B";
        AtomicReference<String> plain = new AtomicReference<>(a);
        AtomicStampedReference<String> stamped = new AtomicStampedReference<>(a, 0);
        int stampSeen = stamped.getStamp();                 // "thread 1" reads A at version 0
        // ...meanwhile "thread 2" changes A -> B -> A:
        plain.set(b);
        plain.set(a);
        stamped.compareAndSet(a, b, 0, 1);
        stamped.compareAndSet(b, a, 1, 2);
        // "thread 1" resumes and tries its CAS:
        check("plain CAS falls for ABA", plain.compareAndSet(a, "C"), true);
        check("stamped CAS detects ABA", stamped.compareAndSet(a, "C", stampSeen, stampSeen + 1), false);
    }

    // =========================================================================
    // 16. SAFE PUBLICATION AND LAZY SINGLETONS
    // =========================================================================
    //
    // "Publishing" an object means making it visible to other threads. Unsafe
    // publication can expose a PARTIALLY CONSTRUCTED object: another thread
    // may see the reference before the constructor's writes. Safe ways:
    //   - store it in a static initializer (class init is thread-safe)
    //   - store it in a volatile field or an AtomicReference
    //   - store it in a final field of a properly constructed object
    //   - guard it with a lock
    //   - put it in a concurrent collection
    // IMMUTABLE objects (all fields final) are thread-safe once constructed.
    // =========================================================================

    /** DOUBLE-CHECKED LOCKING. The `volatile` is ESSENTIAL; without it this is broken. */
    static final class ConfigDcl {
        static final AtomicInteger CREATED = new AtomicInteger();
        private static volatile ConfigDcl instance;

        private ConfigDcl() {
            CREATED.incrementAndGet();
            sleepQuietly(5);                   // widen the race window on purpose
        }

        static ConfigDcl get() {
            ConfigDcl local = instance;        // one volatile read on the fast path
            if (local == null) {               // 1st check (no lock)
                synchronized (ConfigDcl.class) {
                    local = instance;
                    if (local == null) {       // 2nd check (with the lock)
                        instance = local = new ConfigDcl();
                    }
                }
            }
            return local;
        }
    }

    /** INITIALIZATION-ON-DEMAND HOLDER: lazy, thread-safe, no locking code at all.
     *  The JVM loads Holder (and creates INSTANCE) only on the first get() call,
     *  and class initialization is guaranteed to happen exactly once. */
    static final class ConfigHolder {
        static final AtomicInteger CREATED = new AtomicInteger();
        private ConfigHolder() { CREATED.incrementAndGet(); }

        private static final class Holder {
            static final ConfigHolder INSTANCE = new ConfigHolder();
        }

        static ConfigHolder get() { return Holder.INSTANCE; }
    }

    /** ENUM SINGLETON: also safe against serialization and reflection attacks (Effective Java Item 3). */
    enum RateLimiterRegistry {
        INSTANCE;
        private final ConcurrentHashMap<String, Semaphore> limiters = new ConcurrentHashMap<>();
        Semaphore forClient(String client) { return limiters.computeIfAbsent(client, c -> new Semaphore(5)); }
    }

    static void section16_safePublication() throws Exception {
        section("16. Safe publication: DCL, holder idiom, enum singleton");

        Set<Object> dclInstances = ConcurrentHashMap.newKeySet();
        Set<Object> holderInstances = ConcurrentHashMap.newKeySet();
        Set<Object> limiters = ConcurrentHashMap.newKeySet();
        runConcurrently(32, id -> {
            dclInstances.add(ConfigDcl.get());
            holderInstances.add(ConfigHolder.get());
            limiters.add(RateLimiterRegistry.INSTANCE.forClient("acme"));
        });
        check("DCL: one instance, created once", dclInstances.size() + "/" + ConfigDcl.CREATED.get(), "1/1");
        check("holder: one instance, created once", holderInstances.size() + "/" + ConfigHolder.CREATED.get(), "1/1");
        check("enum singleton shares state", limiters.size(), 1);
    }

    // =========================================================================
    // 17. VIRTUAL THREADS (Java 21)
    // =========================================================================
    //
    // Virtual threads are cheap, JVM-managed threads. A blocking call parks
    // the virtual thread and frees its carrier OS thread. So the simple
    // "one thread per task" style scales to hundreds of thousands of
    // concurrent I/O-bound tasks.
    //   - Don't pool them; create one per task.
    //   - They don't speed up CPU-bound work. Use a sized pool or fork/join.
    //   - In Java 21, blocking inside `synchronized` PINS the carrier thread;
    //     prefer ReentrantLock around blocking I/O. (JDK 24 fixed most of this.)
    //   - Limit access to scarce resources with a Semaphore, not a small pool.
    // =========================================================================

    static void section17_virtualThreads() throws Exception {
        section("17. Virtual threads");

        int tasks = 20_000;
        AtomicInteger done = new AtomicInteger();
        long start = System.nanoTime();
        try (ExecutorService vts = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                vts.submit(() -> {
                    sleepQuietly(50);                   // simulated remote call
                    done.incrementAndGet();
                });
            }
        }                                               // close() waits for every task
        long ms = Duration.ofNanos(System.nanoTime() - start).toMillis();
        System.out.printf("      %,d blocking tasks (50 ms each) finished in %d ms%n", tasks, ms);
        check("all virtual-thread tasks done", done.get(), tasks);
        check("much faster than sequential (1,000 s)", ms < 20_000, true);

        Thread vt = Thread.ofVirtual().name("vt-demo").unstarted(() -> {});
        check("Thread.ofVirtual() builder", vt.isVirtual() && vt.getName().equals("vt-demo"), true);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /** Like IntConsumer, but the body may throw checked exceptions (InterruptedException, etc.). */
    @FunctionalInterface
    interface ThrowingTask {
        void run(int threadId) throws Exception;
    }

    /**
     * Starts `threads` platform threads that all begin at the SAME moment
     * (a start gate maximizes contention), waits for all of them, and rethrows
     * the first failure so tests can't silently pass.
     */
    static void runConcurrently(int threads, ThrowingTask task) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> list = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            int id = i;
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    task.run(id);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            }, "worker-" + i);
            list.add(t);
            t.start();
        }
        startGate.countDown();
        for (Thread t : list) t.join();
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
    }

    /** The recommended two-phase shutdown pattern (from the ExecutorService Javadoc). */
    static void shutdownAndAwait(ExecutorService pool) throws InterruptedException {
        pool.shutdown();                                         // stop accepting tasks; let queued ones finish
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            pool.shutdownNow();                                  // interrupt running tasks
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("pool did not terminate");
            }
        }
    }

    static Throwable rootCause(Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();                  // never swallow interrupts
        }
    }

    static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void awaitState(Thread t, Thread.State state) throws InterruptedException {
        for (int i = 0; i < 1_000 && t.getState() != state; i++) Thread.sleep(1);
    }

    @FunctionalInterface
    interface ThrowingSupplier {
        Object get() throws Exception;
    }

    static String throwsName(ThrowingSupplier action) {
        try {
            action.get();
            return "no exception";
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        }
    }

    static String throwsName(Runnable action) {
        try {
            action.run();
            return "no exception";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    private static int passed = 0;

    static synchronized <T> void check(String label, T actual, T expected) {
        if (!Objects.equals(actual, expected)) {
            throw new AssertionError(label + "\n   expected: " + expected + "\n   actual:   " + actual);
        }
        passed++;
        System.out.printf("   ok  %-44s %s%n", label, actual);
    }

    static void section(String title) {
        System.out.printf("%n== %s ==%n", title);
    }
}