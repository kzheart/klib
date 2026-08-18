package me.kzheart.klib.ui;

import java.util.Collections;
import java.util.List;

/** 不可变的分页窗口。 */
public final class Page<T> {
    private final List<T> values;
    private final int index;
    private final int count;

    Page(List<T> values, int index, int count) {
        this.values = Collections.unmodifiableList(values);
        this.index = index;
        this.count = count;
    }

    public List<T> values() {
        return values;
    }

    public int index() {
        return index;
    }

    public int count() {
        return count;
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    public boolean hasNext() {
        return index + 1 < count;
    }
}
