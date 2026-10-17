package me.kzheart.klib.config.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Range { double min() default -Double.MAX_VALUE; double max() default Double.MAX_VALUE; }
