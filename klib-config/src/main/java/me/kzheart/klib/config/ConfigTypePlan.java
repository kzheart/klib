package me.kzheart.klib.config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import kotlin.Metadata;
import me.kzheart.klib.config.annotation.Comment;
import me.kzheart.klib.config.annotation.Key;
import me.kzheart.klib.config.annotation.Range;

/** Shared declarations for mapping and explicit default generation, scoped to the model class. */
final class ConfigTypePlan {
    private static final ClassValue<ConfigTypePlan> PLANS = new ClassValue<ConfigTypePlan>() {
        @Override
        protected ConfigTypePlan computeValue(Class<?> type) {
            return new ConfigTypePlan(type);
        }
    };

    final Class<?> type;
    final List<FieldPlan> fields;
    final Set<String> declaredNames;
    final KotlinConfigPlan kotlin;

    static ConfigTypePlan of(Class<?> type) {
        return PLANS.get(type);
    }

    private ConfigTypePlan(Class<?> type) {
        this.type = type;
        List<Class<?>> hierarchy = new ArrayList<Class<?>>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        List<FieldPlan> declarations = new ArrayList<FieldPlan>();
        Set<String> names = new LinkedHashSet<String>();
        Set<String> mappedNames = new LinkedHashSet<String>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                FieldPlan plan = new FieldPlan(field);
                if (!plan.ignored && (plan.name.trim().isEmpty() || !mappedNames.add(plan.name))) {
                    throw new ConfigException("blank or duplicate field key: " + plan.name);
                }
                // Ignored declarations suppress warnings, but cannot collide with mapped keys.
                names.add(plan.name);
                declarations.add(plan);
            }
        }
        this.fields = Collections.unmodifiableList(declarations);
        this.kotlin = type.isAnnotationPresent(Metadata.class)
                ? new KotlinConfigPlan(type, fields, names) : null;
        this.declaredNames = Collections.unmodifiableSet(names);
    }

    Object defaults() {
        if (kotlin != null) {
            return kotlin.defaults();
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new ConfigException("Cannot create defaults for " + type.getName(), failure);
        }
    }

    static final class FieldPlan {
        final Field field;
        final String name;
        final boolean ignored;
        final Comment comment;
        final Range range;

        FieldPlan(Field field) {
            this.field = field;
            Key alias = field.getAnnotation(Key.class);
            this.name = alias == null ? field.getName() : alias.value();
            this.ignored = Modifier.isStatic(field.getModifiers())
                    || Modifier.isTransient(field.getModifiers()) || field.isSynthetic();
            this.comment = field.getAnnotation(Comment.class);
            this.range = field.getAnnotation(Range.class);
        }
    }
}
