package dev.raylauncher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * The 4-digit code that trades for the pairing key (POST /api/pair/start, /api/pair/finish). One active code, valid
 * TTL ms. A client with TRIES wrong codes is locked out; TOTAL wrong codes from everybody close the code and make the
 * next start wait COOL ms, which bounds a guess spread over many addresses. Pure Java: test/PinGateTest.java.
 */
final class PinGate {
    static final long TTL = 120000, GAP = 3000, COOL = 30000, MAX_COOL = 600000;
    static final int TRIES = 5, TOTAL = 20;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Integer> fails = new HashMap<>();
    private String pin;
    private long expires, lastStart = Long.MIN_VALUE / 2, notBefore;
    private int total, refused;
    private boolean local;

    /** The TV closed its popup: the code stops working at once. A window a phone opened counts as refused. */
    synchronized void close() {
        if (pin != null && !local) refuse(System.currentTimeMillis());
        pin = null;
    }

    /** Someone at the TV asked for a code: always works, whatever a client on the network did before. */
    synchronized String startLocal(long now) {
        if (!open(now)) create();
        local = true;
        expires = now + TTL;
        return pin;
    }

    private void create() {
        pin = String.format("%04d", random.nextInt(10000));
        fails.clear();
        total = 0;
    }

    /** Each window left unused or refused makes the next one wait longer: nobody on the network can nag the TV. */
    private void refuse(long now) {
        notBefore = now + Math.min(MAX_COOL, COOL << Math.min(refused, 8));
        refused++;
    }

    /** No secret leaves: a keyed hash of the phone's nonce and the address the TV was reached at. A relay gets another address. */
    static String proof(String key, String nonce, String host) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder out = new StringBuilder();
            for (byte b : mac.doFinal((nonce + "|" + host).getBytes(StandardCharsets.UTF_8))) out.append(String.format("%02x", b));
            return out.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The code to show on the TV (a new one unless one is active), or null when starts come too often. */
    synchronized String start(long now) {
        if (now - lastStart < GAP || now < notBefore) return null;
        lastStart = now;
        if (open(now)) return pin; // an open window is never extended from the network
        if (now < notBefore) return null;
        create();
        local = false;
        expires = now + TTL;
        return pin;
    }

    /** The active code, or null. */
    synchronized String code(long now) {
        return open(now) ? pin : null;
    }

    synchronized boolean open(long now) {
        if (pin != null && now >= expires) {
            if (!local) refuse(now);
            pin = null;
        }
        return pin != null;
    }

    /** "ok", "pin" (wrong), "closed" (none active or expired) or "locked" (too many from this client). */
    synchronized String finish(long now, String client, String guess) {
        if (!open(now)) return "closed";
        Integer n = fails.get(client);
        if (n != null && n >= TRIES) return "locked";
        if (MessageDigest.isEqual(guess.getBytes(StandardCharsets.UTF_8), pin.getBytes(StandardCharsets.UTF_8))) {
            pin = null;
            refused = 0;
            return "ok";
        }
        fails.put(client, n == null ? 1 : n + 1);
        if (++total >= TOTAL) {
            pin = null;
            refuse(now);
        }
        return "pin";
    }
}
