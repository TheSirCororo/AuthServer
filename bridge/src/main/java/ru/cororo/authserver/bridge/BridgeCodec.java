package ru.cororo.authserver.bridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Binary encoding of {@link BridgeMessage}s, authenticated with HMAC-SHA256 over a shared secret.
 *
 * <pre>
 * byte    format version (1)
 * byte    type (1 authenticated, 2 failed, 3 licensed login)
 * long    timestamp
 * long[2] player UUID
 * utf     username
 * ...     type specific fields
 * byte[32] HMAC-SHA256 of everything above
 * </pre>
 */
public final class BridgeCodec {
    /** Plugin message channel; short enough for pre-1.13 clients (20 characters). */
    public static final String CHANNEL = "authserver:bridge";

    /** Messages older than this are rejected as replays. */
    public static final long MAX_AGE_MILLIS = 60_000;

    private static final int FORMAT = 1;
    private static final int TYPE_AUTHENTICATED = 1;
    private static final int TYPE_FAILED = 2;
    private static final int TYPE_LICENSED_LOGIN = 3;
    private static final int MAC_LENGTH = 32;

    private final SecretKeySpec key;

    public BridgeCodec(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("The bridge secret must be at least 16 characters long");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public byte[] encode(BridgeMessage message) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeByte(FORMAT);
            output.writeByte(switch (message) {
                case BridgeMessage.Authenticated ignored -> TYPE_AUTHENTICATED;
                case BridgeMessage.Failed ignored -> TYPE_FAILED;
                case BridgeMessage.LicensedLogin ignored -> TYPE_LICENSED_LOGIN;
            });
            output.writeLong(message.timestamp());
            output.writeLong(message.playerId().getMostSignificantBits());
            output.writeLong(message.playerId().getLeastSignificantBits());
            output.writeUTF(message.username());
            switch (message) {
                case BridgeMessage.Authenticated authenticated -> {
                    output.writeByte(authenticated.mode().ordinal());
                    output.writeByte(authenticated.method().ordinal());
                    output.writeBoolean(authenticated.target().isPresent());
                    if (authenticated.target().isPresent()) output.writeUTF(authenticated.target().get());
                }
                case BridgeMessage.Failed failed -> {
                    output.writeByte(failed.reason().ordinal());
                    output.writeUTF(failed.detail());
                }
                case BridgeMessage.LicensedLogin licensed -> output.writeUTF(licensed.detail());
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        byte[] body = bytes.toByteArray();
        byte[] signed = Arrays.copyOf(body, body.length + MAC_LENGTH);
        System.arraycopy(mac(body), 0, signed, body.length, MAC_LENGTH);
        return signed;
    }

    /**
     * Verifies and decodes a message.
     *
     * @param now current time in milliseconds, for replay protection
     * @throws InvalidMessageException if the signature, format or age is wrong
     */
    public BridgeMessage decode(byte[] data, long now) throws InvalidMessageException {
        if (data.length <= MAC_LENGTH) throw new InvalidMessageException("Message too short");
        byte[] body = Arrays.copyOf(data, data.length - MAC_LENGTH);
        byte[] signature = Arrays.copyOfRange(data, data.length - MAC_LENGTH, data.length);
        if (!MessageDigest.isEqual(mac(body), signature)) throw new InvalidMessageException("Invalid signature");
        try (var input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readUnsignedByte() != FORMAT) throw new InvalidMessageException("Unsupported message format");
            int type = input.readUnsignedByte();
            long timestamp = input.readLong();
            if (Math.abs(now - timestamp) > MAX_AGE_MILLIS) throw new InvalidMessageException("Message expired");
            var playerId = new UUID(input.readLong(), input.readLong());
            String username = input.readUTF();
            return switch (type) {
                case TYPE_AUTHENTICATED -> new BridgeMessage.Authenticated(playerId, username, timestamp,
                        enumValue(AuthMode.values(), input.readUnsignedByte()),
                        enumValue(AuthMethod.values(), input.readUnsignedByte()),
                        input.readBoolean() ? Optional.of(input.readUTF()) : Optional.empty());
                case TYPE_FAILED -> new BridgeMessage.Failed(playerId, username, timestamp,
                        enumValue(FailureReason.values(), input.readUnsignedByte()), input.readUTF());
                case TYPE_LICENSED_LOGIN -> new BridgeMessage.LicensedLogin(playerId, username, timestamp, input.readUTF());
                default -> throw new InvalidMessageException("Unknown message type " + type);
            };
        } catch (IOException exception) {
            throw new InvalidMessageException("Malformed message: " + exception.getMessage());
        }
    }

    private byte[] mac(byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(body);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        }
    }

    private static <E extends Enum<E>> E enumValue(E[] values, int ordinal) throws InvalidMessageException {
        if (ordinal >= values.length) throw new InvalidMessageException("Unknown enum value " + ordinal);
        return values[ordinal];
    }

    public static final class InvalidMessageException extends Exception {
        public InvalidMessageException(String message) {
            super(message);
        }
    }
}
