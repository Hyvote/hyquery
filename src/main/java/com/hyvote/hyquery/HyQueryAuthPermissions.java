package com.hyvote.hyquery;

/**
 * Endpoint permissions for V2 queries.
 *
 * @param basic   Whether BASIC endpoint is allowed.
 * @param players Whether PLAYERS endpoint is allowed.
 */
public record HyQueryAuthPermissions(boolean basic, boolean players) {

    public static HyQueryAuthPermissions defaults() {
        return new HyQueryAuthPermissions(true, true);
    }

    public static HyQueryAuthPermissions fromLegacyShowPlayerList(boolean showPlayerList) {
        return new HyQueryAuthPermissions(true, showPlayerList);
    }

    public boolean isAllowed(HyQueryV2Protocol.QueryType queryType) {
        return switch (queryType) {
            case BASIC -> basic;
            case PLAYERS -> players;
            case CHALLENGE, UNKNOWN -> true;
        };
    }
}
