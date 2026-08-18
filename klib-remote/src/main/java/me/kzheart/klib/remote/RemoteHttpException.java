package me.kzheart.klib.remote;

import java.io.IOException;

/** Remote HTTP 请求返回非预期状态。 */
public final class RemoteHttpException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int status;
    private final long retryAfterMillis;

    RemoteHttpException(int status) {
        this(status, -1L);
    }

    RemoteHttpException(int status, long retryAfterMillis) {
        super("Remote returned HTTP " + status);
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    /** 返回 HTTP 状态码。 */
    public int status() { return status; }

    /**
     * 返回服务端要求等待的毫秒数；响应未携带有效的 {@code Retry-After} 时返回 {@code -1}。
     */
    public long retryAfterMillis() { return retryAfterMillis; }
}
