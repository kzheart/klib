package me.kzheart.klib.script;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** delay 使用的宿主持有调度器，避免创建引擎线程。 */
@FunctionalInterface
public interface DelayScheduler {

    CompletionStage<Object> delay(Duration duration);
}
