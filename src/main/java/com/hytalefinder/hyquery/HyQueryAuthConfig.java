package com.hytalefinder.hyquery;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * OneQuery-compatible V2 endpoint access configuration.
 *
 * @param publicAccess Permissions for unauthenticated requests.
 * @param tokens       Per-token permissions map.
 */
public record HyQueryAuthConfig(
    HyQueryAuthPermissions publicAccess,
    Map<String, HyQueryAuthPermissions> tokens
) {

    public static HyQueryAuthConfig defaults() {
        return new HyQueryAuthConfig(HyQueryAuthPermissions.defaults(), Map.of());
    }

    public static HyQueryAuthConfig fromLegacyShowPlayerList(boolean showPlayerList) {
        return new HyQueryAuthConfig(HyQueryAuthPermissions.fromLegacyShowPlayerList(showPlayerList), Map.of());
    }

    public static HyQueryAuthConfig withDefaults(HyQueryAuthConfig config) {
        if (config == null) {
            return defaults();
        }

        HyQueryAuthPermissions resolvedPublic =
            config.publicAccess() != null ? config.publicAccess() : HyQueryAuthPermissions.defaults();
        Map<String, HyQueryAuthPermissions> resolvedTokens = normalizeTokens(config.tokens());
        return new HyQueryAuthConfig(resolvedPublic, resolvedTokens);
    }

    public boolean isAccessAllowed(HyQueryV2Protocol.QueryType queryType, byte[] authToken) {
        if (publicAccess.isAllowed(queryType)) {
            return true;
        }

        if (authToken == null || authToken.length == 0) {
            return false;
        }

        HyQueryAuthPermissions tokenPermissions = getTokenPermissions(authToken);
        return tokenPermissions != null && tokenPermissions.isAllowed(queryType);
    }

    private HyQueryAuthPermissions getTokenPermissions(byte[] authToken) {
        if (tokens.isEmpty()) {
            return null;
        }
        String token = new String(authToken, StandardCharsets.UTF_8);
        return tokens.get(token);
    }

    private static Map<String, HyQueryAuthPermissions> normalizeTokens(Map<String, HyQueryAuthPermissions> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }

        Map<String, HyQueryAuthPermissions> normalized = new HashMap<>();
        for (Map.Entry<String, HyQueryAuthPermissions> entry : raw.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            HyQueryAuthPermissions permissions = entry.getValue();
            normalized.put(entry.getKey(), permissions != null ? permissions : HyQueryAuthPermissions.defaults());
        }
        return Map.copyOf(normalized);
    }
}
