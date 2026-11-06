import java.util.LinkedList;
import java.util.Queue;

public class MyBlockingQueue {

    Queue<Integer> queue = new LinkedList<>();
    int maxCapacity = 10;

    public void put(int in){
        queue.add(in);
    }

    public int get(){

        return queue.poll();

    }
}
