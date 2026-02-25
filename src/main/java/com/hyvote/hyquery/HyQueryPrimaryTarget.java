package com.hyvote.hyquery;

/**
 * Represents a primary server target for workers to send status updates to.
 * Used in hub clustering mode where workers push to multiple primaries.
 *
 * @param host Primary server hostname or IP address
 * @param port Primary server port
 */
public record HyQueryPrimaryTarget(
    String host,
    int port
) {
    /**
     * Create a target with default port.
     */
    public static HyQueryPrimaryTarget of(String host) {
        return new HyQueryPrimaryTarget(host, 5520);
    }

    /**
     * Create a target with specified host and port.
     */
    public static HyQueryPrimaryTarget of(String host, int port) {
        return new HyQueryPrimaryTarget(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
