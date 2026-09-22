package me.kzheart.klib.command.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Command { String value(); String[] aliases() default {}; }
