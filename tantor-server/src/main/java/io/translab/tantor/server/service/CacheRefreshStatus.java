package io.translab.tantor.server.service;

/** Read-only freshness metadata for an atomically published cluster cache snapshot. */
public record CacheRefreshStatus(
        State state,
        long lastSuccessfulRefreshMillis,
        long lastAttemptMillis,
        String lastError) {

    public enum State {
        UNINITIALIZED,
        FRESH,
        EMPTY,
        STALE,
        FAILED
    }
}
