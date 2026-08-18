package me.kzheart.klib.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 所有可选集成的不可变启动报告。 */
public final class DependencyReport {

    private final List<Entry> entries;

    private DependencyReport(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<Entry> entries() {
        return entries;
    }

    public List<String> lines() {
        List<String> lines = new ArrayList<String>(entries.size());
        for (Entry entry : entries) {
            String marker = entry.status == DependencyStatus.AVAILABLE ? "✔" : "✖";
            lines.add(marker + " " + entry.dependency + " " + entry.detail);
        }
        return Collections.unmodifiableList(lines);
    }

    /** 一项依赖解析结果。 */
    public static final class Entry {
        private final String dependency;
        private final DependencyStatus status;
        private final String detail;

        private Entry(String dependency, DependencyStatus status, String detail) {
            this.dependency = dependency;
            this.status = status;
            this.detail = detail;
        }

        public String dependency() {
            return dependency;
        }

        public DependencyStatus status() {
            return status;
        }

        public String detail() {
            return detail;
        }
    }

    /** 插件启动期间使用的可变报告构建器。 */
    public static final class Builder {
        private final List<Entry> entries = new ArrayList<Entry>();

        public Builder add(Hook<?> hook) {
            Objects.requireNonNull(hook, "hook");
            entries.add(new Entry(hook.dependency(), hook.status(), hook.detail()));
            return this;
        }

        public DependencyReport build() {
            return new DependencyReport(entries);
        }
    }
}
