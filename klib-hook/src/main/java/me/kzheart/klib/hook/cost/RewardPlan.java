package me.kzheart.klib.hook.cost;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 按顺序发放的一组奖励。某项失败不会中断其余奖励，失败明细记录在 {@link RewardResult} 中。 */
public final class RewardPlan {
    private final List<Reward> rewards;

    RewardPlan(List<Reward> rewards) {
        this.rewards = Collections.unmodifiableList(new ArrayList<Reward>(rewards));
    }

    public static RewardPlan of(List<? extends Reward> rewards) {
        Objects.requireNonNull(rewards, "rewards");
        List<Reward> copy = new ArrayList<Reward>(rewards.size());
        for (Reward reward : rewards) {
            copy.add(Objects.requireNonNull(reward, "reward"));
        }
        return new RewardPlan(copy);
    }

    public List<Reward> rewards() {
        return rewards;
    }

    public boolean isEmpty() {
        return rewards.isEmpty();
    }

    public List<String> describe() {
        List<String> lines = new ArrayList<String>(rewards.size());
        for (Reward reward : rewards) {
            lines.add(reward.describe());
        }
        return Collections.unmodifiableList(lines);
    }

    public RewardResult grant(Player player) {
        Objects.requireNonNull(player, "player");
        List<String> failures = new ArrayList<String>();
        for (Reward reward : rewards) {
            try {
                reward.grant(player);
            } catch (RuntimeException failure) {
                failures.add(reward.describe() + ": " + CostPlan.describe(failure));
            }
        }
        return new RewardResult(rewards.size() - failures.size(), failures);
    }
}
