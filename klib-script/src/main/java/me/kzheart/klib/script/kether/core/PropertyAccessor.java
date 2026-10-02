/* Copyright (c) 2018 Bkm016. MIT License. Upstream: c27e822fb34eebd7433a94efbfac0a26943cccd6 */
package me.kzheart.klib.script.kether.core;

/** 运行时解析 {@code &变量[键]} 与 {@code 动作[键]} 属性读取，由宿主运行时安装到任务服务。 */
@FunctionalInterface
public interface PropertyAccessor {

    Object read(QuestContext.Frame frame, Object instance, String key);
}
