// language: Java, file: StringDecryptor.java, target: Fabric 26.2
package com.baggute.cheatclient.stealth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Runtime string decryption for values that must not appear as plain text in the
 * class file.
 *
 * <p>Format: {@code base64(nonce[0..1] || xored bytes)}. Each byte is XORed with
 * two key bytes (position-dependent) and its own index, so equal plaintexts
 * produce different ciphertexts when encoded with different nonces. This is not
 * cryptography and is not meant to be: it defeats {@code strings | grep}, which
 * is the actual threat model for a class file lying on disk.
 *
 * <p>Encode with {@code java ...StringDecryptor "text"} — the tool prints the
 * literal to paste into a {@code private static final String} field. Compile-time
 * encryption of every literal in the jar needs a bytecode transformer and is not
 * what this class does; it covers the values that identify the client.
 */
public final class StringDecryptor {

    /** 32 bytes, fixed: changing it invalidates every encoded literal in the jar. */
    private static final byte[] KEY = {
            (byte) 0x93, (byte) 0x2B, (byte) 0x6D, (byte) 0x41,
            (byte) 0xC7, (byte) 0x1E, (byte) 0x58, (byte) 0xD4,
            (byte) 0x0A, (byte) 0x7F, (byte) 0xB2, (byte) 0x36,
            (byte) 0xE9, (byte) 0x14, (byte) 0x8C, (byte) 0x63,
            (byte) 0x27, (byte) 0xFA, (byte) 0x45, (byte) 0x9B,
            (byte) 0x18, (byte) 0xCE, (byte) 0x72, (byte) 0x05,
            (byte) 0xB8, (byte) 0x4D, (byte) 0xE1, (byte) 0x3A,
            (byte) 0x96, (byte) 0x5C, (byte) 0x09, (byte) 0xDF
    };

    private StringDecryptor() {
    }

    /** Decodes one literal; returns "" for null or malformed input, never throws. */
    public static String decrypt(String payload) {
        if (payload == null || payload.isEmpty()) return "";
        try {
            byte[] raw = Base64.getDecoder().decode(payload);
            if (raw.length < 2) return "";
            int nonce = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
            byte[] out = new byte[raw.length - 2];
            for (int i = 0; i < out.length; i++) {
                int k1 = KEY[(i + nonce) % KEY.length] & 0xFF;
                int k2 = KEY[(i * 7 + nonce * 3 + 11) % KEY.length] & 0xFF;
                out[i] = (byte) ((raw[i + 2] & 0xFF) ^ k1 ^ k2 ^ (i & 0xFF) ^ (nonce & 0xFF));
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (RuntimeException malformed) {
            return "";
        }
    }

    /** Inverse of {@link #decrypt}; used by the encoder, not on a hot path. */
    public static String encrypt(String plain, int nonce) {
        if (plain == null) return "";
        byte[] in = plain.getBytes(StandardCharsets.UTF_8);
        byte[] raw = new byte[in.length + 2];
        raw[0] = (byte) ((nonce >>> 8) & 0xFF);
        raw[1] = (byte) (nonce & 0xFF);
        for (int i = 0; i < in.length; i++) {
            int k1 = KEY[(i + nonce) % KEY.length] & 0xFF;
            int k2 = KEY[(i * 7 + nonce * 3 + 11) % KEY.length] & 0xFF;
            raw[i + 2] = (byte) (in[i] ^ k1 ^ k2 ^ (i & 0xFF) ^ (nonce & 0xFF));
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Prints a literal for a given plaintext: {@code StringDecryptor "value"}. */
    public static void main(String[] args) {
        if (args.length == 0) return;
        int nonce = 0x5A17;
        System.out.println(encrypt(args[0], nonce));
    }
}
