package me.kzheart.klib.remote;

/** `/ingest/v1/settings` 的公开策略、服务端预算与保留期。 */
public final class RemoteSettings {
    private final String keyStatus;
    private final String environment;
    private final boolean acceptingEvents;
    private final RemotePolicy policy;
    private final Limits limits;
    private final Retention retention;

    RemoteSettings(String keyStatus, String environment, boolean acceptingEvents,
            RemotePolicy policy, Limits limits, Retention retention) {
        this.keyStatus = keyStatus;
        this.environment = environment;
        this.acceptingEvents = acceptingEvents;
        this.policy = policy;
        this.limits = limits;
        this.retention = retention;
    }

    public String keyStatus() { return keyStatus; }
    public String environment() { return environment; }
    public boolean acceptingEvents() { return acceptingEvents; }
    public RemotePolicy policy() { return policy; }
    public Limits limits() { return limits; }
    public Retention retention() { return retention; }

    public static final class Limits {
        private final long maxCompressedBytes;
        private final long maxDecompressedBytes;
        private final int maxBatchEvents;
        private final int maxEventBytes;
        private final int maxMessageBytes;
        private final int maxAttributes;
        private final int maxInstallationIdBytes;
        private final int maxEventIdBytes;
        private final int maxAttributeKeyBytes;
        private final int maxAttributeValueBytes;
        private final int maxEnvironmentFieldBytes;
        private final int keyEventsPerMinute;
        private final int ipEventsPerMinute;
        private final int installationEventsPerMinute;
        private final int asnEventsPerMinute;
        private final int productSpikeEventsPerMinute;

        Limits(long compressed, long decompressed, int batch, int event, int message, int attributes,
                int installationId, int eventId, int attributeKey, int attributeValue,
                int environmentField, int keyEvents, int ipEvents, int installationEvents,
                int asnEvents, int productSpikeEvents) {
            if (compressed < 1L || decompressed < 1L || batch < 1 || event < 1 || message < 1
                    || attributes < 1 || installationId < 1 || eventId < 1 || attributeKey < 1
                    || attributeValue < 1 || environmentField < 1 || keyEvents < 1
                    || ipEvents < 1 || installationEvents < 1 || asnEvents < 1
                    || productSpikeEvents < 1
                    || event > decompressed || message > event || installationId > event
                    || eventId > event || attributeKey > event || attributeValue > event
                    || environmentField > event || batch > keyEvents || batch > ipEvents
                    || batch > installationEvents || batch > asnEvents
                    || batch > productSpikeEvents) {
                throw new IllegalArgumentException("invalid Remote limits");
            }
            maxCompressedBytes = compressed;
            maxDecompressedBytes = decompressed;
            maxBatchEvents = batch;
            maxEventBytes = event;
            maxMessageBytes = message;
            maxAttributes = attributes;
            maxInstallationIdBytes = installationId;
            maxEventIdBytes = eventId;
            maxAttributeKeyBytes = attributeKey;
            maxAttributeValueBytes = attributeValue;
            maxEnvironmentFieldBytes = environmentField;
            keyEventsPerMinute = keyEvents;
            ipEventsPerMinute = ipEvents;
            installationEventsPerMinute = installationEvents;
            asnEventsPerMinute = asnEvents;
            productSpikeEventsPerMinute = productSpikeEvents;
        }
        public long maxCompressedBytes() { return maxCompressedBytes; }
        public long maxDecompressedBytes() { return maxDecompressedBytes; }
        public int maxBatchEvents() { return maxBatchEvents; }
        public int maxEventBytes() { return maxEventBytes; }
        public int maxMessageBytes() { return maxMessageBytes; }
        public int maxAttributes() { return maxAttributes; }
        public int maxInstallationIdBytes() { return maxInstallationIdBytes; }
        public int maxEventIdBytes() { return maxEventIdBytes; }
        public int maxAttributeKeyBytes() { return maxAttributeKeyBytes; }
        public int maxAttributeValueBytes() { return maxAttributeValueBytes; }
        public int maxEnvironmentFieldBytes() { return maxEnvironmentFieldBytes; }
        public int keyEventsPerMinute() { return keyEventsPerMinute; }
        public int ipEventsPerMinute() { return ipEventsPerMinute; }
        public int installationEventsPerMinute() { return installationEventsPerMinute; }
        public int asnEventsPerMinute() { return asnEventsPerMinute; }
        public int productSpikeEventsPerMinute() { return productSpikeEventsPerMinute; }
    }

    public static final class Retention {
        private final int logsDays;
        private final int incidentsDays;
        private final int aggregatesDays;
        Retention(int logs, int incidents, int aggregates) {
            if (logs < 1 || incidents < logs || aggregates < incidents) {
                throw new IllegalArgumentException("invalid Remote retention");
            }
            logsDays = logs; incidentsDays = incidents; aggregatesDays = aggregates;
        }
        public int logsDays() { return logsDays; }
        public int incidentsDays() { return incidentsDays; }
        public int aggregatesDays() { return aggregatesDays; }
    }
}
