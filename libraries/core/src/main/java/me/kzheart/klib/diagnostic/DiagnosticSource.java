package me.kzheart.klib.diagnostic;

import java.util.Map;

/**
 * 提供组件当前的轻量内存诊断快照。
 *
 * <p>实现不得在采集时读文件、访问网络或等待外部服务；需要这些信息时应在正常业务流程中缓存结果。
 */
public interface DiagnosticSource {
    /** 稳定的组件名称，用作一次 Incident 中的快照分组。 */
    String diagnosticName();

    /** 返回当前内存状态；调用方会复制并施加条目数与字节预算。 */
    Map<String, ?> diagnosticSnapshot();
}
