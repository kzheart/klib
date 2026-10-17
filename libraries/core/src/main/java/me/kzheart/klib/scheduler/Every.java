package me.kzheart.klib.scheduler;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Every { long ticks(); TaskThread thread() default TaskThread.SYNC; }
