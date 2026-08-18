package me.kzheart.example.gather;

import java.util.Objects;

/** 供单元测试与服务器测试验证方块采集行为的小型确定性核心。 */
public final class GatherSession {
    public enum Result {
        STARTED,
        PROGRESSED,
        COMPLETED,
        WRONG_TOOL,
        TOOL_BROKEN
    }

    private final String requiredTool;
    private final int maximumHealth;
    private int health;

    public GatherSession(String requiredTool, int maximumHealth) {
        this.requiredTool = Objects.requireNonNull(requiredTool, "requiredTool");
        if (maximumHealth < 1) {
            throw new IllegalArgumentException("maximumHealth must be positive");
        }
        this.maximumHealth = maximumHealth;
        this.health = maximumHealth;
    }

    public Result hit(String tool, int durability, int damage) {
        if (!requiredTool.equalsIgnoreCase(tool == null ? "" : tool)) {
            return Result.WRONG_TOOL;
        }
        if (durability < 1) {
            return Result.TOOL_BROKEN;
        }
        if (damage < 1) {
            throw new IllegalArgumentException("damage must be positive");
        }
        boolean first = health == maximumHealth;
        health = Math.max(0, health - damage);
        if (health == 0) {
            return Result.COMPLETED;
        }
        return first ? Result.STARTED : Result.PROGRESSED;
    }

    public int health() {
        return health;
    }

    public void reset() {
        health = maximumHealth;
    }
}
