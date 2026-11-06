import java.util.concurrent.atomic.AtomicInteger;

public class Runners<T extends Number > implements Runnable{
    T counter;

    public Runners(T counter){
        this.counter=counter;
    }

    @Override
    public void run() {
        synchronized(this) {
            counter.intValue();
        }
    }
}
