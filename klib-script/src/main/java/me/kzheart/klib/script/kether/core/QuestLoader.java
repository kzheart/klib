/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 从字节或路径加载任务源码。 */
public interface QuestLoader {
    default <C extends QuestContext> Quest load(QuestService<C> service, String id, byte[] bytes) {
        return load(service, id, bytes, new ArrayList<>());
    }
    <C extends QuestContext> Quest load(
            QuestService<C> service, String id, Path path, List<String> namespace) throws IOException;
    <C extends QuestContext> Quest load(
            QuestService<C> service, String id, byte[] bytes, List<String> namespace);
}
