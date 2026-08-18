package me.kzheart.klib.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 按顺序应用版本高于文档当前版本的迁移。 */
public final class MigrationRunner {
    private final TreeMap<Integer, List<Migration>> migrations =
            new TreeMap<Integer, List<Migration>>();

    public MigrationRunner add(int targetVersion, Migration migration) {
        if (targetVersion < 1) {
            throw new IllegalArgumentException("targetVersion must be positive");
        }
        Objects.requireNonNull(migration, "migration");
        List<Migration> atVersion = migrations.get(Integer.valueOf(targetVersion));
        if (atVersion == null) {
            atVersion = new ArrayList<Migration>();
            migrations.put(Integer.valueOf(targetVersion), atVersion);
        }
        atVersion.add(migration);
        return this;
    }

    public int migrate(YamlDocument document, int currentVersion) {
        Objects.requireNonNull(document, "document");
        if (currentVersion < 0) {
            throw new IllegalArgumentException("currentVersion must not be negative");
        }
        if (currentVersion > latestVersion()) {
            throw new ConfigException(
                    document.sourceName() + ":_schema-version: document version "
                            + currentVersion + " is newer than supported version " + latestVersion());
        }
        int expectedVersion = currentVersion + 1;
        for (Map.Entry<Integer, List<Migration>> entry : migrations.entrySet()) {
            int targetVersion = entry.getKey().intValue();
            if (targetVersion <= currentVersion) {
                continue;
            }
            if (targetVersion != expectedVersion) {
                throw new ConfigException(
                        document.sourceName() + ":_schema-version: missing migration for version "
                                + expectedVersion + " before version " + targetVersion);
            }
            expectedVersion++;
        }

        int resultingVersion = currentVersion;
        for (Map.Entry<Integer, List<Migration>> entry : migrations.entrySet()) {
            int targetVersion = entry.getKey().intValue();
            if (targetVersion <= currentVersion) {
                continue;
            }
            for (Migration migration : entry.getValue()) {
                migration.apply(document);
            }
            resultingVersion = targetVersion;
            document.setInteger("_schema-version", resultingVersion);
        }
        return resultingVersion;
    }

    public int migrate(YamlDocument document) {
        Objects.requireNonNull(document, "document");
        return migrate(document, document.schemaVersion());
    }

    public int latestVersion() {
        return migrations.isEmpty() ? 0 : migrations.lastKey().intValue();
    }
}
