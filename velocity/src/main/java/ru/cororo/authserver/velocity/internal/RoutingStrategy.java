package ru.cororo.authserver.velocity.internal;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/** Picks one server of a group. */
public enum RoutingStrategy {
    RANDOM {
        @Override
        Optional<RegisteredServer> pick(List<RegisteredServer> servers) {
            return Optional.of(servers.get(ThreadLocalRandom.current().nextInt(servers.size())));
        }
    },
    LEAST_PLAYERS {
        @Override
        Optional<RegisteredServer> pick(List<RegisteredServer> servers) {
            return servers.stream().min(Comparator.comparingInt(server -> server.getPlayersConnected().size()));
        }
    },
    ROUND_ROBIN {
        private final AtomicInteger next = new AtomicInteger();

        @Override
        Optional<RegisteredServer> pick(List<RegisteredServer> servers) {
            return Optional.of(servers.get(Math.floorMod(next.getAndIncrement(), servers.size())));
        }
    },
    FIRST {
        @Override
        Optional<RegisteredServer> pick(List<RegisteredServer> servers) {
            return Optional.of(servers.getFirst());
        }
    };

    /** @param servers non-empty group */
    abstract Optional<RegisteredServer> pick(List<RegisteredServer> servers);

    public Optional<RegisteredServer> choose(List<RegisteredServer> servers) {
        return servers.isEmpty() ? Optional.empty() : pick(servers);
    }
}
