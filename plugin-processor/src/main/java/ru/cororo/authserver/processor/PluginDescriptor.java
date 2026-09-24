package ru.cororo.authserver.processor;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Plugin descriptor gathered from {@code @AuthServerPlugin}, written as {@code authserver-plugin.json}.
 * Shared by the Java annotation processor and the KSP processor so both produce identical files.
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String main,
        String description,
        List<String> authors,
        List<String> depends,
        List<String> softDepends) {

    public static final String FILE = "authserver-plugin.json";
    public static final String ANNOTATION = "ru.cororo.authserver.api.plugin.AuthServerPlugin";
    public static final String PLUGIN_CLASS = "ru.cororo.authserver.api.plugin.Plugin";
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,64}");

    public PluginDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(main, "main");
        name = name == null || name.isEmpty() ? id : name;
        version = version == null || version.isEmpty() ? "unknown" : version;
        description = description == null ? "" : description;
        authors = List.copyOf(authors);
        depends = List.copyOf(depends);
        softDepends = List.copyOf(softDepends);
    }

    /** Problems that make the descriptor unusable, empty when it is valid. */
    public List<String> problems() {
        var problems = new java.util.ArrayList<String>();
        if (!ID.matcher(id).matches()) problems.add("Plugin id '" + id + "' must be 1-64 lowercase letters, digits, '-' or '_'");
        for (var dependency : depends) {
            if (!ID.matcher(dependency).matches()) problems.add("Dependency id '" + dependency + "' is invalid");
        }
        for (var dependency : softDepends) {
            if (!ID.matcher(dependency).matches()) problems.add("Dependency id '" + dependency + "' is invalid");
        }
        if (depends.contains(id) || softDepends.contains(id)) problems.add("A plugin cannot depend on itself");
        return problems;
    }

    public String toJson() {
        return "{\n"
                + "  \"id\": " + quote(id) + ",\n"
                + "  \"name\": " + quote(name) + ",\n"
                + "  \"version\": " + quote(version) + ",\n"
                + "  \"main\": " + quote(main) + ",\n"
                + "  \"description\": " + quote(description) + ",\n"
                + "  \"authors\": " + array(authors) + ",\n"
                + "  \"depends\": " + array(depends) + ",\n"
                + "  \"softDepends\": " + array(softDepends) + "\n"
                + "}\n";
    }

    private static String array(List<String> values) {
        return values.stream().map(PluginDescriptor::quote).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String quote(String value) {
        var escaped = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format("\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.append('"').toString();
    }
}
