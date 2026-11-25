import java.util.*;

public class LruCache<K,V> {

    Map<K,V> lru;
    int capacity;
    List<K> latest;


    public LruCache( int capacity) {
        this.lru = new HashMap<>(capacity);
        this.capacity = capacity;
        this.latest = new LinkedList<>();
    }

    public void put(K key, V value)
    {
        lru.put(key,value);
        latest.remove(key);
        latest.addFirst(key);
        if(latest.size()>capacity){
            lru.remove(latest.removeLast());

        }

    }

    public V get(K key){
        if(!lru.containsKey(key)){
            return null;
        }
        latest.remove(key);
        latest.addFirst(key);
        return lru.get(key);


    }

    @Override
    public String toString() {
        return lru.toString() + " list: " + latest.toString();
    }

    public static void main(String[] args) {
        LruCache<Integer, String> lru = new LruCache<>(2);
        lru.put(1, "A");
        System.out.println(lru);
        lru.put(2, "B");
        System.out.println(lru);
        lru.get(1);       // Moves 1 to front; 2 is now at the back
        System.out.println(lru);
        lru.put(3, "C");  // Evicts 2
        System.out.println(lru);

        System.out.println(lru.get(2)); // Output: null
        System.out.println(lru.get(1)); // Output: A
    }
}
