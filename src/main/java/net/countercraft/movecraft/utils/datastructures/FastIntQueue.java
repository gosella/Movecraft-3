package net.countercraft.movecraft.utils.datastructures;


public class FastIntQueue {
    private int capacity;
    private int front;
    private int length;
    private int[] data;

    public FastIntQueue(int capacity) {
        capacity = Math.max(16, 2 * Integer.highestOneBit(capacity - 1));
        this.front = 0;
        this.length = 0;
        this.capacity = capacity;
        this.data = new int[capacity];
    }

    public int size() {
        return this.length;
    }

    public boolean isEmpty() {
        return this.length == 0;
    }

    public void enqueue(int value) {
        if (this.length == this.capacity) {
            this.capacity *= 2;
            final int[] newData = new int[this.capacity];
            System.arraycopy(this.data, 0, newData, 0, this.length);
            this.data = newData;
        }
        int i = this.front + this.length;
        if (i >= this.capacity)
            i -= capacity;
        ++this.length;
        this.data[i] = value;
    }

    public int dequeue() {
        final int value = this.data[this.front];
        if (++this.front == this.capacity) {
            this.front = 0;
        }
        --this.length;
        return value;
    }
}
