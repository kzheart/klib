package me.kzheart.klib.ui.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Menu { String title(); String[] layout(); }
