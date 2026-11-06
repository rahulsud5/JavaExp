import java.util.concurrent.atomic.AtomicInteger;

public class ThreadMain {

    static volatile AtomicInteger common;

    public ThreadMain() {
    }

    public static void main(String[] args) throws InterruptedException {

        Thread t1 = new Thread(new Runners(common));
        t1.start();
        t1.join();
        System.out.println(common);

    }
}
