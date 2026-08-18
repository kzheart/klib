package me.kzheart.klib.remote;

import java.io.IOException;

/** Remote 返回了无法按 `/ingest/v1` 解码的响应。 */
public final class RemoteProtocolException extends IOException {
    private static final long serialVersionUID = 1L;
    RemoteProtocolException(String message) { super(message); }
}
