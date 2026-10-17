package me.kzheart.klib.config;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 多文件配置聚合的单引用发布边界。解析、跨文件校验与不可变复制由 prepare 的构建器完成。
 * 不提交 ConfigSource、不回滚磁盘或监听器副作用，也不冻结业务对象内部的可变字段。
 * 不持有作用域、监听器或后台任务；停止与作用域关闭后禁止提交由调用者协调。
 */
public final class ConfigSnapshot<T> {
    private final AtomicReference<State<T>> current;

    public ConfigSnapshot(T initialValue) {
        current = new AtomicReference<State<T>>(new State<T>(
                Objects.requireNonNull(initialValue, "initialValue"), 0));
    }

    /** 读取一次并在整次业务操作内复用，避免跨版本混读。 */
    public T value() {
        return current.get().value;
    }

    /** 当前发布修订，供诊断使用；与分开调用的 value() 不构成原子读取对。 */
    public long revision() {
        return current.get().revision;
    }

    /**
     * 在调用线程构建候选；任一异常原样传播且已发布值不变。
     * 构建器应只读取、解析、校验和复制，不修改已发布对象或执行业务副作用。
     * 允许部分文件失败时，调用者须显式收集诊断并在返回前验证剩余集合的引用完整性。
     */
    public Candidate<T> prepare(Supplier<? extends T> builder) {
        Objects.requireNonNull(builder, "builder");
        State<T> base = current.get();
        T value = Objects.requireNonNull(builder.get(), "candidate value");
        return new Candidate<T>(this, base, value);
    }

    /**
     * 仅当基线仍是 prepare 开始时的快照才交换。候选仅属于创建它的实例且只能提交一次。
     * 旧候选绝不覆盖较新的成功提交；拒绝时需重新解析校验，不自动重试构建器。
     */
    public void commit(Candidate<T> candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.owner != this) {
            throw new IllegalArgumentException("Candidate belongs to another configuration snapshot");
        }
        State<T> next = new State<T>(candidate.value, Math.addExact(candidate.base.revision, 1));
        if (!current.compareAndSet(candidate.base, next)) {
            throw new ConfigException("Configuration candidate is stale or already committed");
        }
    }

    /** 已校验候选，不会隐式发布或提交源文件。 */
    public static final class Candidate<T> {
        private final ConfigSnapshot<T> owner;
        private final State<T> base;
        private final T value;

        private Candidate(ConfigSnapshot<T> owner, State<T> base, T value) {
            this.owner = owner;
            this.base = base;
            this.value = value;
        }

        public T value() {
            return value;
        }

        public long baseRevision() {
            return base.revision;
        }
    }

    private static final class State<T> {
        private final T value;
        private final long revision;

        private State(T value, long revision) {
            this.value = value;
            this.revision = revision;
        }
    }
}
