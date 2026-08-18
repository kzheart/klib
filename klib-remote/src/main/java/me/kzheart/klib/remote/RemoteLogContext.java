package me.kzheart.klib.remote;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一条 Remote 日志的有限 context、MDC 与标签。 */
public final class RemoteLogContext {
    private final Map<String, String> context;
    private final Map<String, String> mdc;
    private final List<String> tags;

    private RemoteLogContext(Builder builder) {
        context = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.context));
        mdc = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.mdc));
        tags = Collections.unmodifiableList(new ArrayList<String>(builder.tags));
    }

    public static Builder builder() { return new Builder(); }
    Map<String, String> context() { return context; }
    Map<String, String> mdc() { return mdc; }
    List<String> tags() { return tags; }

    public static final class Builder {
        private final Map<String, String> context = new LinkedHashMap<String, String>();
        private final Map<String, String> mdc = new LinkedHashMap<String, String>();
        private final List<String> tags = new ArrayList<String>();

        public Builder context(String key, Object value) {
            put(context, key, value, "context"); return this;
        }
        public Builder context(Map<String, ?> values) {
            for (Map.Entry<String, ?> entry : values.entrySet()) context(entry.getKey(), entry.getValue());
            return this;
        }
        public Builder mdc(String key, Object value) {
            put(mdc, key, value, "mdc"); return this;
        }
        public Builder tag(String value) {
            String tag = Texts.requireText(value, "tag");
            if (tags.size() >= 16) throw new IllegalStateException("tags exceed limit");
            requireBytes(tag, 64, "tag");
            tags.add(tag);
            return this;
        }
        public RemoteLogContext build() { return new RemoteLogContext(this); }

        private static void put(Map<String, String> target, String key, Object value, String kind) {
            String normalizedKey = Texts.requireText(key, kind + " key");
            String normalizedValue = String.valueOf(value);
            if (!target.containsKey(normalizedKey) && target.size() >= 32) {
                throw new IllegalStateException(kind + " fields exceed limit");
            }
            requireBytes(normalizedKey, 64, kind + " key");
            requireBytes(normalizedValue, 1024, kind + " value");
            target.put(normalizedKey, normalizedValue);
        }

        private static void requireBytes(String value, int limit, String name) {
            if (value.getBytes(StandardCharsets.UTF_8).length > limit) {
                throw new IllegalArgumentException(name + " exceeds byte limit");
            }
        }
    }
}
