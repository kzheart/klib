package me.kzheart.klib.remote;

import java.util.Collections;
import java.util.List;

/** 服务端对一个 batch 的逐事件接收结果。 */
public final class RemoteBatchResult {
    private final List<EventResult> results;
    private final int accepted;
    private final int duplicate;
    private final int rejected;

    RemoteBatchResult(List<EventResult> results, int accepted, int duplicate, int rejected) {
        this.results = Collections.unmodifiableList(results);
        this.accepted = accepted;
        this.duplicate = duplicate;
        this.rejected = rejected;
    }

    /** 返回与请求索引一一对应的逐事件结果。 */
    public List<EventResult> results() { return results; }
    /** 返回首次接受的事件数。 */
    public int accepted() { return accepted; }
    /** 返回按 {@code event_id} 幂等去重的事件数。 */
    public int duplicate() { return duplicate; }
    /** 返回永久拒绝的事件数。 */
    public int rejected() { return rejected; }

    /** 一条事件的接收结果。 */
    public static final class EventResult {
        private final int index;
        private final String eventId;
        private final Status status;
        private final String error;

        EventResult(int index, String eventId, Status status, String error) {
            this.index = index;
            this.eventId = eventId;
            this.status = status;
            this.error = error;
        }
        /** 返回事件在请求 batch 中的索引。 */
        public int index() { return index; }
        /** 返回与请求索引对应的事件 ID。 */
        public String eventId() { return eventId; }
        /** 返回该事件的终结状态。 */
        public Status status() { return status; }
        /** 返回永久拒绝原因；非拒绝结果返回 {@code null}。 */
        public String error() { return error; }
    }

    /** 服务端对单个事件给出的终结状态。 */
    public enum Status {
        ACCEPTED,
        DUPLICATE,
        REJECTED;

        static Status fromWire(String value) {
            if ("accepted".equals(value)) return ACCEPTED;
            if ("duplicate".equals(value)) return DUPLICATE;
            if ("rejected".equals(value)) return REJECTED;
            throw new IllegalArgumentException("unsupported result status");
        }
    }
}
