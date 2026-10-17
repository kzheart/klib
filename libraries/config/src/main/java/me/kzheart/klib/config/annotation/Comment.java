package me.kzheart.klib.config.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Comment { String[] value(); }
