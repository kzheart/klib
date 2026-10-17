package me.kzheart.klib.remote;

import java.util.Map;

/** 为 Incident 提供模块快照的 SPI；实现必须把采集控制在调用方给出的预算内。 */
public interface DiagnosticContributor {
    String name();

    Map<String, ?> contribute(Context context) throws Exception;

    final class Context {
        private final Throwable error;
        private final RemoteOperation.Context operation;

        Context(Throwable error, RemoteOperation.Context operation) {
            this.error = error;
            this.operation = operation;
        }

        public Throwable error() { return error; }
        public RemoteOperation.Context operation() { return operation; }
    }
}
