/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 用于 `def name = { ... }` Kether 源码的默认加载器。 */
public class SimpleQuestLoader implements QuestLoader {
    @Override
    public <C extends QuestContext> Quest load(
            QuestService<C> service, String id, Path path, List<String> namespace) throws IOException {
        return load(service, id, Files.readAllBytes(path), namespace);
    }

    @Override
    public <C extends QuestContext> Quest load(
            QuestService<C> service, String id, byte[] bytes, List<String> namespace) {
        return newBlockReader(new String(bytes, StandardCharsets.UTF_8).toCharArray(), service, namespace).parse(id);
    }

    protected BlockReader newBlockReader(char[] content, QuestService<?> service, List<String> namespace) {
        return new BlockReader(content, service, namespace);
    }
}
