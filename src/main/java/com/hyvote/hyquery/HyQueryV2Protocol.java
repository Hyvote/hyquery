package com.hyvote.hyquery;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * OneQuery V2 protocol constants and request parsing helpers.
 */
public final class HyQueryV2Protocol {

    public static final byte VERSION = 0x01;

    public static final byte[] REQUEST_MAGIC_ONEQUERY = "ONEQUERY".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] REQUEST_MAGIC_HYQUERY2 = "HYQUERY2".getBytes(StandardCharsets.US_ASCII);

    public static final byte[] RESPONSE_MAGIC_ONEREPLY = "ONEREPLY".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] RESPONSE_MAGIC_HYREPLY2 = "HYREPLY2".getBytes(StandardCharsets.US_ASCII);

    public static final byte TYPE_CHALLENGE = 0x00;
    public static final byte TYPE_BASIC = 0x01;
    public static final byte TYPE_PLAYERS = 0x02;

    public static final short FLAG_REQUEST_HAS_AUTH_TOKEN = 0x0001;

    public static final short FLAG_RESPONSE_HAS_MORE_PLAYERS = 0x0001;
    public static final short FLAG_RESPONSE_AUTH_REQUIRED = 0x0002;
    public static final short FLAG_RESPONSE_IS_NETWORK = 0x0010;
    public static final short FLAG_RESPONSE_HAS_ADDRESS = 0x0020;

    public static final int CHALLENGE_TOKEN_SIZE = 32;
    public static final int REQUEST_ID_SIZE = 4;
    public static final int HEADER_SIZE = 17;
    public static final int SAFE_MTU = 1400;

    public static final int QUERY_TYPE_SIZE = 1;
    public static final int QUERY_FLAGS_SIZE = 2;
    public static final int QUERY_OFFSET_SIZE = 4;

    private HyQueryV2Protocol() {}

    public enum RequestFamily {
        ONEQUERY(REQUEST_MAGIC_ONEQUERY, RESPONSE_MAGIC_ONEREPLY),
        HYQUERY2(REQUEST_MAGIC_HYQUERY2, RESPONSE_MAGIC_HYREPLY2);

        private final byte[] requestMagic;
        private final byte[] responseMagic;

        RequestFamily(byte[] requestMagic, byte[] responseMagic) {
            this.requestMagic = requestMagic;
            this.responseMagic = responseMagic;
        }

        public byte[] requestMagic() {
            return requestMagic;
        }

        public byte[] responseMagic() {
            return responseMagic;
        }

        public int offsetType() {
            return requestMagic.length;
        }

        public int offsetChallengeToken() {
            return offsetType() + QUERY_TYPE_SIZE;
        }

        public int offsetRequestId() {
            return offsetChallengeToken() + CHALLENGE_TOKEN_SIZE;
        }

        public int offsetFlags() {
            return offsetRequestId() + REQUEST_ID_SIZE;
        }

        public int offsetPagination() {
            return offsetFlags() + QUERY_FLAGS_SIZE;
        }

        public int offsetOptionalData() {
            return offsetPagination() + QUERY_OFFSET_SIZE;
        }

        public int minChallengeSize() {
            return requestMagic.length + QUERY_TYPE_SIZE;
        }

        public int minQueryTokenSize() {
            return requestMagic.length + QUERY_TYPE_SIZE + CHALLENGE_TOKEN_SIZE;
        }

        public int minQueryHeaderSize() {
            return offsetOptionalData();
        }
    }

    public enum QueryType {
        CHALLENGE,
        BASIC,
        PLAYERS,
        UNKNOWN
    }

    public static QueryType getQueryType(byte rawType) {
        return switch (rawType) {
            case TYPE_CHALLENGE -> QueryType.CHALLENGE;
            case TYPE_BASIC -> QueryType.BASIC;
            case TYPE_PLAYERS -> QueryType.PLAYERS;
            default -> QueryType.UNKNOWN;
        };
    }

    public static boolean isV2Request(ByteBuf buf) {
        RequestFamily family = detectRequestFamily(buf);
        return family != null && buf.readableBytes() >= family.minChallengeSize();
    }

    public static boolean isKnownV2Packet(ByteBuf buf) {
        return detectRequestFamily(buf) != null || matchesAnyResponseMagic(buf);
    }

    public static RequestFamily detectRequestFamily(ByteBuf buf) {
        if (matchesMagic(buf, REQUEST_MAGIC_ONEQUERY)) {
            return RequestFamily.ONEQUERY;
        }
        if (matchesMagic(buf, REQUEST_MAGIC_HYQUERY2)) {
            return RequestFamily.HYQUERY2;
        }
        return null;
    }

    public static boolean matchesAnyResponseMagic(ByteBuf buf) {
        return matchesMagic(buf, RESPONSE_MAGIC_ONEREPLY) || matchesMagic(buf, RESPONSE_MAGIC_HYREPLY2);
    }

    public static ParsedRequest parseRequest(ByteBuf buf) {
        RequestFamily family = detectRequestFamily(buf);
        if (family == null || buf.readableBytes() < family.minChallengeSize()) {
            return ParsedRequest.error("invalid V2 request magic or packet too short");
        }

        byte rawType = buf.getByte(buf.readerIndex() + family.offsetType());
        QueryType queryType = getQueryType(rawType);

        if (queryType == QueryType.CHALLENGE) {
            return ParsedRequest.challenge(family, rawType);
        }

        if (buf.readableBytes() < family.minQueryTokenSize()) {
            return ParsedRequest.error("missing V2 challenge token");
        }

        if (buf.readableBytes() < family.minQueryHeaderSize()) {
            return ParsedRequest.error("missing V2 query header fields");
        }

        int base = buf.readerIndex();

        byte[] challengeToken = new byte[CHALLENGE_TOKEN_SIZE];
        buf.getBytes(base + family.offsetChallengeToken(), challengeToken);

        int requestId = buf.getIntLE(base + family.offsetRequestId());
        short flags = buf.getShortLE(base + family.offsetFlags());
        int offset = buf.getIntLE(base + family.offsetPagination());

        byte[] authToken = null;
        if ((flags & FLAG_REQUEST_HAS_AUTH_TOKEN) != 0) {
            if (buf.readableBytes() < family.offsetOptionalData() + 2) {
                return ParsedRequest.error("auth token flag set but auth token length is missing");
            }

            int authLength = buf.getShortLE(base + family.offsetOptionalData()) & 0xFFFF;
            int authStart = base + family.offsetOptionalData() + 2;
            int packetEnd = base + buf.readableBytes();
            if (packetEnd < authStart + authLength) {
                return ParsedRequest.error("auth token length exceeds packet size");
            }

            authToken = new byte[authLength];
            buf.getBytes(authStart, authToken);
        }

        return ParsedRequest.query(family, rawType, queryType, requestId, flags, offset, challengeToken, authToken);
    }

    private static boolean matchesMagic(ByteBuf buf, byte[] magic) {
        if (buf.readableBytes() < magic.length) {
            return false;
        }

        int readerIndex = buf.readerIndex();
        for (int i = 0; i < magic.length; i++) {
            if (buf.getByte(readerIndex + i) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    public record ParsedRequest(
        boolean valid,
        String error,
        RequestFamily family,
        byte rawType,
        QueryType queryType,
        int requestId,
        short flags,
        int offset,
        byte[] challengeToken,
        byte[] authToken
    ) {
        private static ParsedRequest error(String error) {
            return new ParsedRequest(false, error, null, (byte) 0, QueryType.UNKNOWN, 0, (short) 0, 0, null, null);
        }

        private static ParsedRequest challenge(RequestFamily family, byte rawType) {
            return new ParsedRequest(true, null, family, rawType, QueryType.CHALLENGE, 0, (short) 0, 0, null, null);
        }

        private static ParsedRequest query(
            RequestFamily family,
            byte rawType,
            QueryType queryType,
            int requestId,
            short flags,
            int offset,
            byte[] challengeToken,
            byte[] authToken
        ) {
            return new ParsedRequest(true, null, family, rawType, queryType, requestId, flags, offset, challengeToken, authToken);
        }
    }
}
