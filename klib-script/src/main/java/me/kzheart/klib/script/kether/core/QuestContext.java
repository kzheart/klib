/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/** 一次任务执行的运行时状态与帧树。 */
public interface QuestContext {

    String BASE_BLOCK = "main";
    QuestService<? extends QuestContext> getService();
    Quest getQuest();
    void setExitStatus(ExitStatus exitStatus);
    Optional<ExitStatus> getExitStatus();
    CompletableFuture<Object> runActions();
    Executor getExecutor();
    void terminate();
    Frame rootFrame();

    interface Frame extends AutoCloseable {
        String name();
        QuestContext context();
        Optional<ParsedAction<?>> currentAction();
        List<Frame> children();

        default Stream<Frame> walkFrames() { return walkFrames(Integer.MAX_VALUE); }
        default Stream<Frame> walkFrames(int depth) {
            if (depth < 0) return Stream.empty();
            if (depth == 0) return Stream.of(this);
            Stream<Frame> frames = Stream.of(this);
            for (Frame child : children()) frames = Stream.concat(frames, child.walkFrames(depth - 1));
            return frames;
        }

        Optional<Frame> parent();
        void setNext(ParsedAction<?> action);
        void setNext(Quest.Block block);
        Frame newFrame(String name);
        Frame newFrame(ParsedAction<?> action);
        VarTable variables();

        /**
         * 动作完成后会立即调用该可关闭资源。
         *
         * @param closeable 要清理的资源
         */
        <T extends AutoCloseable> T addClosable(T closeable);

        <T> CompletableFuture<T> run();
        @Override void close();
        boolean isDone();
    }

    interface VarTable {
        <T> Optional<T> get(String name) throws CompletionException;
        default <T> T getOrDefault(String name, T defaultValue) { return this.<T>get(name).orElse(defaultValue); }
        default <T> T getOrNull(String name) { return this.<T>get(name).orElse(null); }
        <T> Optional<QuestFuture<T>> getFuture(String name);
        void set(String name, Object value);
        void remove(String name);
        void clear();
        <T> void set(String name, ParsedAction<T> owner, CompletableFuture<T> future);
        Set<String> keys();
        Collection<Map.Entry<String, Object>> values();
        default Map<String, Object> toMap() {
            Map<String, Object> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : values()) result.put(entry.getKey(), entry.getValue());
            return result;
        }
        void initialize(Frame frame);
        void close();
        VarTable parent();
    }
}
