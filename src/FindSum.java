import java.util.*;

public class FindSum {

    class Node {
        int data;
        Node next;

        Node(int data) {
            this.data = data;
            this.next = null;
        }
    }

    public static void main(String[] args) {
        findSums();
        findPalindrome();
        validParanthesis();
        binarySearch();
        reverseList();
    }

    private static void reverseList() {

    }
    

    private static void binarySearch() {
        int target = 5;
        List<Integer> list  = Arrays.asList(-2,-1,0,2,3,4,5,6);

        int low=0, high = list.size()-1;
        while(high>low) {
            int mid = (low + high) / 2;
            if (target > list.get(mid)) {
                low = mid+1;

            } else if (target < list.get(mid)) {
                high = mid-1;
            }
            if(target==list.get(mid)){
                System.out.println(target + " found at "+ mid);
                return ;
            }
        }
        System.out.println("target not found.");

    }



    private static void validParanthesis() {

        Map<Character, Character> complements = new HashMap<>();
        complements.put('}','{');
        complements.put(']','[');
        complements.put(')','(');
        Stack<Character> stack = new Stack<>();
        String input = "{[({})]}";
        int i=0;
        while(i<input.length()){
            Character curr = input.charAt(i);
            if("{([".indexOf(curr) >= 0){
                stack.add(curr);
            }else {
                if(complements.get(curr)!=stack.pop()){
                    System.out.println("mismatch");
                    break;
                }
            }
            i++;
        }
    }

    private static void findPalindrome() {
        String word = "abcddcba";

        int i =0;
        int j = word.length()-1;
        while (j>i && word.charAt(i)==word.charAt(j)){
            i++;
            j--;
        }
        if(j>i){
            System.out.println("Not a palindrome");
        }
    }

    private static void findSums() {
        HashMap<Integer,Integer> complements = new HashMap<>(10);
        List<Integer> nums = Arrays.asList(-1,0,1,2,3,4,5);
        int target = 3;
        for(int i=0; i<nums.size(); i++){
            Integer compIntLocation = complements.get(target-nums.get(i));
            if(compIntLocation!=null){
                System.out.println(i+"+"+compIntLocation);
            } else {
                complements.put(nums.get(i),i);
            }
        }
    }
}
