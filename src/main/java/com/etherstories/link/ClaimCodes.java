package com.etherstories.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

/** 离线服 UUID 可被冒名。跨服取回与钱包取出使用6 位码。钱包码可在本服钱包页再看、自行修改。 */
final class ClaimCodes {
    private static final SecureRandom RNG = new SecureRandom();

    private ClaimCodes() {}

    static String generate() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder d = new StringBuilder(6);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') d.append(c);
        }
        return d.toString();
    }

    static boolean plausible(String code) {
        return normalize(code).length() == 6;
    }

    static String listingHash(String code) {
        return sha("eslink-claim-v1|" + normalize(code));
    }

    static String walletHash(UUID uuid, String code) {
        return sha("eslink-wallet-v1|" + uuid + "|" + normalize(code));
    }

    static boolean listingOk(String code, String hash) {
        if (hash == null || hash.isBlank() || !plausible(code)) return false;
        return listingHash(code).equalsIgnoreCase(hash);
    }

    static boolean walletOk(UUID uuid, String code, String hash) {
        if (uuid == null || hash == null || hash.isBlank() || !plausible(code)) return false;
        return walletHash(uuid, code).equalsIgnoreCase(hash);
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
