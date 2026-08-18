package me.kzheart.klib.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 不可变的有序结构定义。 */
public final class Schema {
    private final String name;
    private final List<Migration> migrations;

    public Schema(String name, List<Migration> migrations) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (migrations == null) {
            throw new NullPointerException("migrations");
        }
        List<Migration> ordered = new ArrayList<Migration>(migrations);
        Collections.sort(ordered, Comparator.comparingInt(Migration::version));
        Set<Integer> versions = new HashSet<Integer>();
        for (Migration migration : ordered) {
            if (!versions.add(migration.version())) {
                throw new IllegalArgumentException("duplicate migration version " + migration.version());
            }
        }
        this.name = name;
        this.migrations = Collections.unmodifiableList(ordered);
    }

    public String name() {
        return name;
    }

    public List<Migration> migrations() {
        return migrations;
    }
}
