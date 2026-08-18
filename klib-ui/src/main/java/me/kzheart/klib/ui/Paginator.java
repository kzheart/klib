package me.kzheart.klib.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 导航范围受限、结果确定的零基分页。 */
public final class Paginator<T> {
    private final List<T> values;
    private final int pageSize;

    public Paginator(List<T> values, int pageSize) {
        Objects.requireNonNull(values, "values");
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.values = Collections.unmodifiableList(new ArrayList<T>(values));
        this.pageSize = pageSize;
    }

    public int pageCount() {
        return Math.max(1, (values.size() + pageSize - 1) / pageSize);
    }

    public Page<T> page(int requestedIndex) {
        int index = Math.max(0, Math.min(requestedIndex, pageCount() - 1));
        int from = Math.min(values.size(), index * pageSize);
        int to = Math.min(values.size(), from + pageSize);
        return new Page<T>(new ArrayList<T>(values.subList(from, to)), index, pageCount());
    }
}
