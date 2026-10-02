package com.danidipp.sneakypocketbase;

import java.util.Map;
import java.util.Objects;

/** Immutable, atomically read lifecycle state. Revisions and generations survive handler reloads. */
public final class PocketbaseLifecycleSnapshot {
    public enum ApiState { STARTING, AUTHENTICATING, AVAILABLE, UNAVAILABLE, AUTHENTICATION_FAILED, STOPPED }
    public enum TransportState { DISCONNECTED, CONNECTING, CONNECTED, STOPPED }
    public enum SubscriptionState { PENDING, READY, FAILED }

    public record CollectionStatus(SubscriptionState state, String reason) {
        public CollectionStatus {
            Objects.requireNonNull(state);
            Objects.requireNonNull(reason);
        }
    }

    private final ApiState apiState;
    private final TransportState transportState;
    private final Map<String, CollectionStatus> collections;
    private final long generation;
    private final long revision;
    private final String reason;

    public PocketbaseLifecycleSnapshot(ApiState apiState, TransportState transportState,
            Map<String, CollectionStatus> collections, long generation, long revision, String reason) {
        this.apiState = Objects.requireNonNull(apiState);
        this.transportState = Objects.requireNonNull(transportState);
        this.collections = Map.copyOf(collections);
        this.generation = generation;
        this.revision = revision;
        this.reason = Objects.requireNonNull(reason);
    }

    public ApiState getApiState() { return apiState; }
    public TransportState getTransportState() { return transportState; }
    public Map<String, CollectionStatus> getCollections() { return collections; }
    public long getGeneration() { return generation; }
    public long getRevision() { return revision; }
    public String getReason() { return reason; }

    public boolean isCollectionReady(String collection) {
        CollectionStatus status = collections.get(collection);
        return apiState == ApiState.AVAILABLE && transportState == TransportState.CONNECTED
            && status != null && status.state() == SubscriptionState.READY;
    }
}
