import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Dumps packet IDs from an official, obfuscated server (1.14.4 - 1.20.4) using Mojang's server mappings.
 * The ConnectionProtocol object graph is walked reflectively, so internal field layout changes do not matter.
 * Output lines: {@code state flow MojangClassName id}.
 * Usage: java -cp <server classpath> PacketIdDump.java server_mappings.txt
 */
public class PacketIdDump {
    private static final Map<String, String> CLASSES = new HashMap<>();
    private static final Map<String, Map<String, String>> FIELDS = new HashMap<>();
    private static final Set<String> RESULTS = new TreeSet<>();
    private static final Set<Object> SEEN = Collections.newSetFromMap(new IdentityHashMap<>());

    public static void main(String[] args) throws Exception {
        readMappings(Path.of(args[0]));
        String protocolClass = obfuscated("net.minecraft.network.ConnectionProtocol");
        Class<?> type = Class.forName(protocolClass);
        for (Object constant : type.getEnumConstants()) {
            String state = fieldName(type, ((Enum<?>) constant).name());
            visit(constant, state, null, 0);
        }
        RESULTS.forEach(System.out::println);
    }

    private static void visit(Object value, String state, String flow, int depth) throws Exception {
        if (value == null || depth > 10 || value instanceof Class<?> || value instanceof String
                || value instanceof Number || value instanceof Boolean || !SEEN.add(value)) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey(), mapped = entry.getValue();
                if (key instanceof Class<?> packet && mapped instanceof Integer id) record(state, flow, packet, id);
                else if (key instanceof Integer id && mapped instanceof Class<?> packet) record(state, flow, packet, id);
                else if (key instanceof Enum<?> e && isFlow(e)) visit(mapped, state, flowName(e), depth + 1);
                else {
                    visit(key, state, flow, depth + 1);
                    visit(mapped, state, flow, depth + 1);
                }
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) visit(element, state, flow, depth + 1);
            return;
        }
        if (value.getClass().isArray()) {
            if (value.getClass().getComponentType().isPrimitive()) return;
            for (int i = 0; i < Array.getLength(value); i++) visit(Array.get(value, i), state, flow, depth + 1);
            return;
        }
        if (value instanceof Enum<?> e && depth > 0) return; // Other enum constants are not part of this state.
        if (value.getClass().getName().startsWith("java.")) return;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    visit(field.get(value), state, flow, depth + 1);
                } catch (RuntimeException ignored) {
                    // Inaccessible JDK internals are irrelevant for packet tables.
                }
            }
        }
    }

    private static void record(String state, String flow, Class<?> packet, int id) {
        String name = CLASSES.getOrDefault(packet.getName(), packet.getName());
        if (name.startsWith("net.minecraft.network.protocol.") && flow != null) {
            RESULTS.add(state + " " + flow + " " + name.substring(name.lastIndexOf('.') + 1).replace('$', '.') + " " + id);
        }
    }

    private static boolean isFlow(Enum<?> constant) {
        return "net.minecraft.network.protocol.PacketFlow".equals(CLASSES.get(constant.getDeclaringClass().getName()));
    }

    private static String flowName(Enum<?> constant) {
        return fieldName(constant.getDeclaringClass(), constant.name()).toLowerCase(Locale.ROOT);
    }

    private static String fieldName(Class<?> owner, String obfuscatedField) {
        return FIELDS.getOrDefault(CLASSES.get(owner.getName()), Map.of()).getOrDefault(obfuscatedField, obfuscatedField);
    }

    private static String obfuscated(String named) {
        return CLASSES.entrySet().stream().filter(e -> e.getValue().equals(named)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No mapping for " + named)).getKey();
    }

    private static void readMappings(Path path) throws Exception {
        String current = null;
        for (String line : Files.readAllLines(path)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            if (!line.startsWith(" ")) {
                String[] parts = line.substring(0, line.length() - 1).split(" -> ");
                current = parts[0];
                CLASSES.put(parts[1], parts[0]);
            } else if (!line.contains("(")) {
                String[] parts = line.trim().split(" -> ");
                String[] declaration = parts[0].split(" ");
                FIELDS.computeIfAbsent(current, ignored -> new HashMap<>()).put(parts[1], declaration[declaration.length - 1]);
            }
        }
    }
}
