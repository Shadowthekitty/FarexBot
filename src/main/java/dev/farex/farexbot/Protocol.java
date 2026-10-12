package dev.farex.farexbot;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whisper payloads exchanged between sender and loader.
 *
 * <pre>
 *   pull: fxp1 &lt;sender&gt; &lt;unixSeconds&gt; &lt;nonce&gt; &lt;mac&gt;
 *   ack:  fxa1 &lt;nonce&gt; &lt;status&gt; &lt;mac&gt;
 * </pre>
 *
 * The MAC is HMAC-SHA256 over the fields (plus the loader's name for pulls) with the shared secret, so random
 * players cannot trigger a chamber and a captured whisper cannot be replayed.
 */
public final class Protocol {
    public static final Pattern PULL = Pattern.compile("fxp1 ([A-Za-z0-9_]{1,16}) (\\d{1,12}) ([0-9a-f]{8}) ([0-9a-f]{16})");
    public static final Pattern ACK = Pattern.compile("fxa1 ([0-9a-f]{8}) ([a-z_]{1,16}) ([0-9a-f]{16})");

    private static final long MAX_AGE_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Long> SEEN_NONCES = new HashMap<>();

    public record Pull(String sender, long timestamp, String nonce, String mac) {
    }

    public record Ack(String nonce, String status, String mac) {
    }

    private Protocol() {
    }

    public static String newNonce() {
        return String.format("%08x", RANDOM.nextInt());
    }

    public static String newSecret() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String buildPull(String sender, String loader, String secret, String nonce) {
        long ts = System.currentTimeMillis() / 1000;
        return "fxp1 " + sender + " " + ts + " " + nonce + " " + pullMac(secret, sender, ts, nonce, loader);
    }

    public static String buildAck(String nonce, String status, String secret) {
        return "fxa1 " + nonce + " " + status + " " + mac(secret, "fxa1|" + nonce + "|" + status);
    }

    public static Pull parsePull(String text) {
        Matcher m = PULL.matcher(text);
        if (!m.find()) return null;
        return new Pull(m.group(1), Long.parseLong(m.group(2)), m.group(3), m.group(4));
    }

    public static Ack parseAck(String text) {
        Matcher m = ACK.matcher(text);
        if (!m.find()) return null;
        return new Ack(m.group(1), m.group(2), m.group(3));
    }

    /** Checks MAC, freshness and replay. Records the nonce only when everything else passes. */
    public static synchronized boolean verifyPull(Pull pull, String loader, String secret) {
        String expected = pullMac(secret, pull.sender(), pull.timestamp(), pull.nonce(), loader);
        if (!constantTimeEquals(expected, pull.mac())) return false;

        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - pull.timestamp()) > MAX_AGE_SECONDS) return false;

        SEEN_NONCES.values().removeIf(seenAt -> now - seenAt > MAX_AGE_SECONDS * 2);
        String key = pull.sender().toLowerCase(Locale.ROOT) + ":" + pull.nonce();
        return SEEN_NONCES.putIfAbsent(key, now) == null;
    }

    public static boolean verifyAck(Ack ack, String secret) {
        return constantTimeEquals(mac(secret, "fxa1|" + ack.nonce() + "|" + ack.status()), ack.mac());
    }

    private static String pullMac(String secret, String sender, long ts, String nonce, String loader) {
        return mac(secret, "fxp1|" + sender.toLowerCase(Locale.ROOT) + "|" + ts + "|" + nonce + "|"
                + loader.toLowerCase(Locale.ROOT));
    }

    private static String mac(String secret, String data) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = hmac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
