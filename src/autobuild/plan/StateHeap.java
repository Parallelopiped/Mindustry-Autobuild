package autobuild.plan;

import java.util.Arrays;

/** Reusable min-heap for search entries; no object allocation on each relaxation.
 * Equal priorities follow PriorityQueue's tie handling to preserve routing choices. */
final class StateHeap{
    private double[] priorities = new double[256];
    private int[] states = new int[256];
    private int size;

    void clear(){ size = 0; }
    boolean isEmpty(){ return size == 0; }
    double priority(){ return priorities[0]; }

    void add(double priority, int state){
        if(size == states.length){
            states = Arrays.copyOf(states, size * 2);
            priorities = Arrays.copyOf(priorities, size * 2);
        }
        int i = size++;
        while(i > 0){
            int parent = (i - 1) >>> 1;
            if(Double.compare(priority, priorities[parent]) >= 0) break;
            priorities[i] = priorities[parent]; states[i] = states[parent];
            i = parent;
        }
        priorities[i] = priority; states[i] = state;
    }

    int poll(){
        int result = states[0], state = states[--size];
        double priority = priorities[size];
        int i = 0;
        while(i < size / 2){
            int child = i * 2 + 1;
            if(child + 1 < size && Double.compare(priorities[child], priorities[child + 1]) > 0) child++;
            if(Double.compare(priority, priorities[child]) <= 0) break;
            priorities[i] = priorities[child]; states[i] = states[child];
            i = child;
        }
        priorities[i] = priority; states[i] = state;
        return result;
    }
}
