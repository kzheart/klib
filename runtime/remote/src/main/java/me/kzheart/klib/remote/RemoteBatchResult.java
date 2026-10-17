package me.kzheart.klib.remote;

import java.util.List;

/** Batch 接收结果的 Java 平台类型声明；快照由 Kotlin 维护。 */
public final class RemoteBatchResult {
    private final RemoteBatchValues state;
    RemoteBatchResult(List<EventResult> results, int accepted, int duplicate, int rejected) {
        state = new RemoteBatchValues(results, accepted, duplicate, rejected);
    }
    public List<EventResult> results() { return state.getResults(); }
    public int accepted() { return state.getAccepted(); }
    public int duplicate() { return state.getDuplicate(); }
    public int rejected() { return state.getRejected(); }

    public static final class EventResult {
        private final RemoteEventReceipt state;
        EventResult(int index, String eventId, Status status, String error) {
            state = new RemoteEventReceipt(index, eventId, status, error);
        }
        public int index() { return state.getIndex(); }
        public String eventId() { return state.getEventId(); }
        public Status status() { return state.getStatus(); }
        public String error() { return state.getError(); }
    }
    public enum Status {
        ACCEPTED, DUPLICATE, REJECTED;
        static Status fromWire(String value) { return RemoteBatchStatus.parse(value); }
    }
}
