package com.hytalefinder.hyquery;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Generates and validates address-bound challenge tokens for V2 queries.
 *
 * Token format (32 bytes):
 * - bytes 0..3: timestamp window (big-endian)
 * - bytes 4..7: reserved (zero)
 * - bytes 8..31: HMAC-SHA256(timestamp + clientAddress), truncated to 24 bytes
 */
public final class HyQueryChallengeService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int TIMESTAMP_GRANULARITY_SECONDS = 30;
    private static final int DEFAULT_SECRET_LENGTH = 32;

    private final SecretKeySpec secretKeySpec;
    private final ThreadLocal<Mac> threadLocalMac;
    private final int validityWindows;

    public HyQueryChallengeService(byte[] secret, int validitySeconds) {
        this.secretKeySpec = new SecretKeySpec(secret.clone(), HMAC_ALGORITHM);
        this.threadLocalMac = ThreadLocal.withInitial(this::createMac);

        int clampedValidity = Math.max(1, validitySeconds);
        this.validityWindows = Math.max(1,
            (clampedValidity + TIMESTAMP_GRANULARITY_SECONDS - 1) / TIMESTAMP_GRANULARITY_SECONDS);
    }

    public static HyQueryChallengeService fromConfig(HyQueryConfig config) {
        String configuredSecret = config.challengeSecret();
        byte[] secret;

        if (configuredSecret == null || configuredSecret.isBlank()) {
            secret = new byte[DEFAULT_SECRET_LENGTH];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        }

        return new HyQueryChallengeService(secret, config.challengeTokenValiditySeconds());
    }

    public byte[] generateToken(InetAddress clientAddress) {
        int timestamp = getCurrentTimestampWindow();
        return generateTokenForTimestamp(clientAddress, timestamp);
    }

    public boolean validateToken(byte[] token, InetAddress clientAddress) {
        if (token == null || token.length != HyQueryV2Protocol.CHALLENGE_TOKEN_SIZE) {
            return false;
        }

        int tokenTimestamp = ByteBuffer.wrap(token, 0, 4).getInt();
        int currentTimestamp = getCurrentTimestampWindow();

        for (int i = 0; i < validityWindows; i++) {
            int expectedTimestamp = currentTimestamp - i;
            if (tokenTimestamp == expectedTimestamp) {
                byte[] expectedToken = generateTokenForTimestamp(clientAddress, tokenTimestamp);
                return constantTimeEquals(token, expectedToken);
            }
        }

        return false;
    }

    private byte[] generateTokenForTimestamp(InetAddress clientAddress, int timestamp) {
        byte[] token = new byte[HyQueryV2Protocol.CHALLENGE_TOKEN_SIZE];

        token[0] = (byte) (timestamp >> 24);
        token[1] = (byte) (timestamp >> 16);
        token[2] = (byte) (timestamp >> 8);
        token[3] = (byte) timestamp;
        token[4] = 0;
        token[5] = 0;
        token[6] = 0;
        token[7] = 0;

        byte[] hmac = computeHmac(timestamp, clientAddress);
        System.arraycopy(hmac, 0, token, 8, 24);

        return token;
    }

    private byte[] computeHmac(int timestamp, InetAddress clientAddress) {
        Mac mac = threadLocalMac.get();

        byte[] addressBytes = clientAddress.getAddress();
        ByteBuffer input = ByteBuffer.allocate(4 + addressBytes.length);
        input.putInt(timestamp);
        input.put(addressBytes);

        return mac.doFinal(input.array());
    }

    private Mac createMac() {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKeySpec);
            return mac;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Unable to initialize challenge HMAC", e);
        }
    }

    private static int getCurrentTimestampWindow() {
        return (int) (System.currentTimeMillis() / 1000 / TIMESTAMP_GRANULARITY_SECONDS);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}
