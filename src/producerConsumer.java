import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class producerConsumer {

    public static void main(String[] args) {

        usingBlockingQueue();

        int counter =0;

    }

    private static void usingBlockingQueue() {
        boolean isDataAvailable;

        BlockingQueue<Integer> queue = new ArrayBlockingQueue<>(10);
        Runnable producer = () -> {
            try {
                while (true) {
                    queue.put(1);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        };

        new Thread(producer).start();
        Runnable consumer = () ->{
            while (true){
                try {
                    System.out.println(
                                        queue.take());
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        };
        new Thread(consumer).start();
    }
}
