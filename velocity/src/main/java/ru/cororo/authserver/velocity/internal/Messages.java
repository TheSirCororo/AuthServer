package ru.cororo.authserver.velocity.internal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** Proxy command texts in MiniMessage, bundled in English and Russian; the client locale picks one. */
final class Messages {
    private static final Map<String, Properties> LANGUAGES = Map.of("en", load("en"), "ru", load("ru"));

    private Messages() {
    }

    static Component render(Locale locale, String key, String... placeholders) {
        Properties language = LANGUAGES.getOrDefault(locale == null ? "en" : locale.getLanguage(), LANGUAGES.get("en"));
        String template = language.getProperty(key, LANGUAGES.get("en").getProperty(key, key));
        TagResolver.Builder resolver = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            resolver.resolver(Placeholder.unparsed(placeholders[i], placeholders[i + 1]));
        }
        return MiniMessage.miniMessage().deserialize(template, resolver.build());
    }

    private static Properties load(String language) {
        try (InputStream stream = Messages.class.getResourceAsStream("/messages_" + language + ".properties")) {
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
