package me.kzheart.example.gather;

import me.kzheart.klib.scope.Disposable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** 管理单个插件作用域内活跃的方块采集会话。 */
public final class GatherRuntime implements Disposable {
    private final int maximumHealth;
    private final Map<String, GatherSession> sessions = new HashMap<String, GatherSession>();

    public GatherRuntime(int maximumHealth) {
        if (maximumHealth < 1) {
            throw new IllegalArgumentException("maximumHealth must be positive");
        }
        this.maximumHealth = maximumHealth;
    }

    public GatherSession.Result hit(String blockKey, String tool, int durability) {
        Objects.requireNonNull(blockKey, "blockKey");
        if (!"mining".equalsIgnoreCase(tool == null ? "" : tool)) {
            return GatherSession.Result.WRONG_TOOL;
        }
        if (durability < 1) {
            return GatherSession.Result.TOOL_BROKEN;
        }

        GatherSession session = sessions.get(blockKey);
        if (session == null) {
            session = new GatherSession("mining", maximumHealth);
            sessions.put(blockKey, session);
        }
        GatherSession.Result result = session.hit(tool, durability, 1);
        if (result == GatherSession.Result.COMPLETED) {
            sessions.remove(blockKey);
        }
        return result;
    }

    public int activeSessions() {
        return sessions.size();
    }

    @Override
    public void dispose() {
        sessions.clear();
    }
}
