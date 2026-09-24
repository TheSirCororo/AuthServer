package ru.cororo.authserver.bridge;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BridgeCodecTest {
    private static final String SECRET = "0123456789abcdef-secret";
    private final BridgeCodec codec = new BridgeCodec(SECRET);

    @Test
    void roundTripsEveryMessageType() throws Exception {
        long now = 1_700_000_000_000L;
        var authenticated = new BridgeMessage.Authenticated(UUID.randomUUID(), "Steve", now, AuthMode.OFFLINE,
                AuthMethod.REGISTER, Optional.of("survival"));
        assertEquals(authenticated, codec.decode(codec.encode(authenticated), now));
        var failed = new BridgeMessage.Failed(UUID.randomUUID(), "Alex", now, FailureReason.WRONG_PASSWORD, "3 attempts");
        assertEquals(failed, codec.decode(codec.encode(failed), now + 1000));
        var licensed = new BridgeMessage.LicensedLogin(UUID.randomUUID(), "Buyer", now, "Reconnect");
        assertEquals(licensed, codec.decode(codec.encode(licensed), now));
    }

    @Test
    void rejectsTamperedForeignAndStaleMessages() {
        long now = 1_700_000_000_000L;
        byte[] data = codec.encode(new BridgeMessage.Authenticated(UUID.randomUUID(), "Steve", now, AuthMode.ONLINE,
                AuthMethod.PREMIUM, Optional.empty()));
        byte[] tampered = data.clone();
        tampered[20] ^= 1;
        assertThrows(BridgeCodec.InvalidMessageException.class, () -> codec.decode(tampered, now));
        assertThrows(BridgeCodec.InvalidMessageException.class,
                () -> new BridgeCodec("another-secret-value").decode(data, now));
        assertThrows(BridgeCodec.InvalidMessageException.class,
                () -> codec.decode(data, now + BridgeCodec.MAX_AGE_MILLIS + 1));
    }

    @Test
    void refusesShortSecrets() {
        assertThrows(IllegalArgumentException.class, () -> new BridgeCodec("short"));
    }
}
