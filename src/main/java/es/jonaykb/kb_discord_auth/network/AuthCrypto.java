package es.jonaykb.kb_discord_auth.network;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Crypto shared by client and server so the session token never travels over the connection, which offline-mode
 * servers don't encrypt:
 * <ul>
 * <li><b>Proof.</b> On join the server sends a random nonce; the client answers HMAC(sha256(token), context + nonce).
 *     The server only stores sha256(token), which is the HMAC key, so it can check the answer.</li>
 * <li><b>New token.</b> Both sides make a throwaway X25519 key pair and derive the token from the shared secret.
 *     Only the public keys are sent.</li>
 * </ul>
 * The context (server id + player UUID) stops an answer or a token from being used for another server or account.
 */
public final class AuthCrypto {
    public static final int NONCE_BYTES = 32;
    /** X.509-encoded X25519 public keys are 44 bytes; leave room. */
    public static final int MAX_PUBLIC_KEY_BYTES = 128;
    public static final int PROOF_LENGTH = 64;

    private AuthCrypto() {}

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Client side: the answer to the server's nonce. */
    public static String proof(String token, String serverId, UUID player, byte[] nonce) {
        return proofFromHash(sha256Hex(token), serverId, player, nonce);
    }

    /** Server side: the answer expected from a client whose token hashes to {@code tokenHash}. */
    public static String proofFromHash(String tokenHash, String serverId, UUID player, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenHash.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(context("proof", serverId, player));
            mac.update(nonce);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Parses a public key received from the other side, or null if it isn't a valid X25519 key. */
    @Nullable
    public static PublicKey readPublicKey(byte[] encoded) {
        if (encoded.length == 0) return null;
        try {
            PublicKey key = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(encoded));
            return "X25519".equals(key.getAlgorithm()) || "XDH".equals(key.getAlgorithm()) ? key : null;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The session token both sides get from their own private key and the other's public key, or null if the other
     * key is unusable (e.g. a small-order point, which would make the secret predictable).
     */
    @Nullable
    public static String deriveToken(PrivateKey own, PublicKey other, String serverId, UUID player) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance("XDH");
            ka.init(own);
            ka.doPhase(other, true);
            byte[] shared = ka.generateSecret();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(context("token", serverId, player));
            md.update(shared);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest());
        } catch (GeneralSecurityException | IllegalStateException e) {
            return null;
        }
    }

    private static byte[] context(String purpose, String serverId, UUID player) {
        return ("kb_discord_auth/" + purpose + "/" + serverId + "/" + player + "/").getBytes(StandardCharsets.UTF_8);
    }
}
