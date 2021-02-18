package quiltmc;

public class Mutable<T> {
    private T value = null;

    public synchronized void set(T value) {
        this.value = value;
    }

    public T get() {
        return this.value;
    }
}
