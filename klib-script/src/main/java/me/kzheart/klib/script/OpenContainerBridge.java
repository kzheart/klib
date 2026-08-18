package me.kzheart.klib.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 在发现的 TabooLib 容器之间按顺序回退。
 *
 * <p>协议特定的发现和通道调用保持可注入，使 Bukkit 适配器可以跨越类加载器，
 * 而不会将 TabooLib 类型泄漏到公开脚本 API 中。</p>
 */
public final class OpenContainerBridge implements UnknownStatementResolver {

    private final ContainerDiscovery discovery;

    public OpenContainerBridge(ContainerDiscovery discovery) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    @Override
    public CompletionStage<Object> resolve(String statement, ScriptContext context) {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(context, "context");
        List<? extends RemoteStatementSource> discovered = discovery.discover();
        List<RemoteStatementSource> sources = discovered == null
                ? Collections.<RemoteStatementSource>emptyList()
                : new ArrayList<RemoteStatementSource>(discovered);
        return resolveAt(sources, 0, statement, context);
    }

    private static CompletionStage<Object> resolveAt(
            List<RemoteStatementSource> sources,
            int index,
            String statement,
            ScriptContext context
    ) {
        if (index >= sources.size()) {
            CompletableFuture<Object> failed = new CompletableFuture<Object>();
            failed.completeExceptionally(new IllegalArgumentException(
                    "No shared statement resolved: " + statement));
            return failed;
        }
        RemoteStatementSource source = sources.get(index);
        CompletionStage<RemoteResolution> attempt = Objects.requireNonNull(
                source.resolve(statement, context),
                "Remote statement source returned null CompletionStage");
        CompletableFuture<Object> result = new CompletableFuture<Object>();
        attempt.whenComplete((resolution, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else if (resolution != null && resolution.isResolved()) {
                result.complete(resolution.value());
            } else {
                resolveAt(sources, index + 1, statement, context).whenComplete(
                        (value, nextFailure) -> {
                            if (nextFailure == null) {
                                result.complete(value);
                            } else {
                                result.completeExceptionally(nextFailure);
                            }
                        });
            }
        });
        return result;
    }

    /** 查找当前服务器的活动协议适配器。 */
    @FunctionalInterface
    public interface ContainerDiscovery {

        List<? extends RemoteStatementSource> discover();
    }

    /** 一个类加载器安全的 TabooLib OpenContainer 适配器。 */
    @FunctionalInterface
    public interface RemoteStatementSource {

        CompletionStage<RemoteResolution> resolve(String statement, ScriptContext context);
    }

    /** 区分未解析语句与已解析的 null 结果。 */
    public static final class RemoteResolution {

        private static final RemoteResolution UNRESOLVED = new RemoteResolution(false, null);

        private final boolean resolved;
        private final Object value;

        private RemoteResolution(boolean resolved, Object value) {
            this.resolved = resolved;
            this.value = value;
        }

        public static RemoteResolution unresolved() {
            return UNRESOLVED;
        }

        public static RemoteResolution resolved(Object value) {
            return new RemoteResolution(true, value);
        }

        public boolean isResolved() {
            return resolved;
        }

        public Object value() {
            return value;
        }
    }
}
