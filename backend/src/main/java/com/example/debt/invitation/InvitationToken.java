package com.example.debt.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** Transient bearer secret, returned only by the issuing operation. */
public record InvitationToken(String value) {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    public InvitationToken {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid invitation token");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (decoded.length != 32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value)) {
            throw new IllegalArgumentException("Invalid invitation token");
        }
    }

    public static InvitationToken generate() {
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        return new InvitationToken(Base64.getUrlEncoder().withoutPadding().encodeToString(entropy));
    }

    public String hash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    @Override
    public String toString() {
        return "InvitationToken[REDACTED]";
    }
}
