package me.kzheart.klib.remote;

import java.time.Duration;
import java.util.Objects;

/** Incident 客户端采集预算，限制异常图、日志窗口、Breadcrumb 与 Contributor。 */
public final class IncidentBudget {
    private final int throwableDepth;
    private final int framesPerThrowable;
    private final int suppressedPerThrowable;
    private final int throwableNodes;
    private final int logEntries;
    private final int logBytes;
    private final int breadcrumbs;
    private final int breadcrumbBytes;
    private final int maxContributors;
    private final long contributorTimeoutMillis;
    private final int contributorEntries;
    private final int contributorBytes;

    private IncidentBudget(Builder builder) {
        throwableDepth = builder.throwableDepth;
        framesPerThrowable = builder.framesPerThrowable;
        suppressedPerThrowable = builder.suppressedPerThrowable;
        throwableNodes = builder.throwableNodes;
        logEntries = builder.logEntries;
        logBytes = builder.logBytes;
        breadcrumbs = builder.breadcrumbs;
        breadcrumbBytes = builder.breadcrumbBytes;
        maxContributors = builder.maxContributors;
        contributorTimeoutMillis = builder.contributorTimeoutMillis;
        contributorEntries = builder.contributorEntries;
        contributorBytes = builder.contributorBytes;
    }

    public static IncidentBudget defaults() { return builder().build(); }
    public static Builder builder() { return new Builder(); }
    int throwableDepth() { return throwableDepth; }
    int framesPerThrowable() { return framesPerThrowable; }
    int suppressedPerThrowable() { return suppressedPerThrowable; }
    int throwableNodes() { return throwableNodes; }
    int logEntries() { return logEntries; }
    int logBytes() { return logBytes; }
    int breadcrumbs() { return breadcrumbs; }
    int breadcrumbBytes() { return breadcrumbBytes; }
    int maxContributors() { return maxContributors; }
    long contributorTimeoutMillis() { return contributorTimeoutMillis; }
    int contributorEntries() { return contributorEntries; }
    int contributorBytes() { return contributorBytes; }

    public static final class Builder {
        private int throwableDepth = 32;
        private int framesPerThrowable = 256;
        private int suppressedPerThrowable = 32;
        private int throwableNodes = 256;
        private int logEntries = 128;
        private int logBytes = 256 * 1024;
        private int breadcrumbs = 64;
        private int breadcrumbBytes = 64 * 1024;
        private int maxContributors = 16;
        private long contributorTimeoutMillis = 100L;
        private int contributorEntries = 64;
        private int contributorBytes = 64 * 1024;

        public Builder throwableDepth(int value) { throwableDepth = positive(value, "throwableDepth"); return this; }
        public Builder framesPerThrowable(int value) { framesPerThrowable = positive(value, "framesPerThrowable"); return this; }
        public Builder suppressedPerThrowable(int value) { suppressedPerThrowable = positive(value, "suppressedPerThrowable"); return this; }
        public Builder throwableNodes(int value) { throwableNodes = positive(value, "throwableNodes"); return this; }
        public Builder logEntries(int value) { logEntries = positive(value, "logEntries"); return this; }
        public Builder logBytes(int value) { logBytes = positive(value, "logBytes"); return this; }
        public Builder breadcrumbs(int value) { breadcrumbs = positive(value, "breadcrumbs"); return this; }
        public Builder breadcrumbBytes(int value) { breadcrumbBytes = positive(value, "breadcrumbBytes"); return this; }
        public Builder maxContributors(int value) { maxContributors = positive(value, "maxContributors"); return this; }
        public Builder contributorEntries(int value) { contributorEntries = positive(value, "contributorEntries"); return this; }
        public Builder contributorBytes(int value) { contributorBytes = positive(value, "contributorBytes"); return this; }

        public Builder contributorTimeout(Duration value) {
            Objects.requireNonNull(value, "contributorTimeout");
            if (value.isZero() || value.isNegative() || value.toMillis() < 1L) {
                throw new IllegalArgumentException("contributorTimeout must be positive");
            }
            contributorTimeoutMillis = value.toMillis();
            return this;
        }

        public IncidentBudget build() { return new IncidentBudget(this); }

        private static int positive(int value, String name) {
            if (value < 1) throw new IllegalArgumentException(name + " must be positive");
            return value;
        }
    }
}
