package com.hytalefinder.hyquery;

/**
 * Worker entry in the primary's authorized workers list.
 *
 * @param id  Worker identifier (supports * wildcard suffix, e.g., "minigame-*")
 * @param key Shared HMAC secret for authentication
 */
public record HyQueryWorkerEntry(
    String id,
    String key
) {
    /**
     * Check if this entry matches a worker ID.
     * Supports wildcard suffix matching (e.g., "minigame-*" matches "minigame-bedwars-1").
     *
     * @param workerId The worker ID to match
     * @return true if this entry matches the worker ID
     */
    public boolean matches(String workerId) {
        if (id == null || workerId == null) {
            return false;
        }

        if (id.endsWith("*")) {
            String prefix = id.substring(0, id.length() - 1);
            return workerId.startsWith(prefix);
        }

        return id.equals(workerId);
    }
}
