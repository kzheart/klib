package me.kzheart.klib.config;

import java.util.Objects;

/** 内置的注释保留迁移。 */
public final class Migrations {
    private Migrations() {
    }

    public static Migration rename(String sourcePath, String targetPath) {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(targetPath, "targetPath");
        if (sourcePath.trim().isEmpty() || targetPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Migration paths must not be blank");
        }
        return document -> document.rename(sourcePath, targetPath);
    }
}
