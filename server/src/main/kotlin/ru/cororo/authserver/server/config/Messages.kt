package ru.cororo.authserver.server.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * Player-facing texts per language. Bundled languages are copied into the messages directory on first start
 * so they can be edited; keys missing from an edited file fall back to the bundled text.
 */
class Messages private constructor(private val languages: Map<String, Map<String, String>>, private val defaultLanguage: String) {
    private val miniMessage = MiniMessage.miniMessage()

    /** @param placeholders pairs of placeholder name and value, inserted as plain text */
    fun render(locale: Locale, key: String, vararg placeholders: Pair<String, Any>): Component {
        val template = languages[locale.language]?.get(key) ?: languages[defaultLanguage]?.get(key) ?: languages["en"]?.get(key) ?: key
        val resolver = TagResolver.resolver(placeholders.map { (name, value) -> Placeholder.unparsed(name, value.toString()) })
        return miniMessage.deserialize(template, resolver)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(Messages::class.java)
        private val bundled = listOf("en", "ru")
        private val format = MapSerializer(String.serializer(), String.serializer())

        fun load(directory: Path, defaultLanguage: String): Messages {
            Files.createDirectories(directory)
            val languages = HashMap<String, Map<String, String>>()
            for (language in bundled) {
                val text = checkNotNull(Messages::class.java.getResourceAsStream("/messages/$language.yml")).use {
                    it.readBytes().decodeToString()
                }
                val file = directory.resolve("$language.yml")
                if (Files.notExists(file)) Files.writeString(file, text)
                languages[language] = Yaml.default.decodeFromString(format, text)
            }
            Files.list(directory).use { files ->
                files.filter { it.extension == "yml" }.forEach { file ->
                    runCatching { Yaml.default.decodeFromString(format, Files.readString(file)) }
                        .onSuccess { edited -> languages[file.nameWithoutExtension] = languages[file.nameWithoutExtension].orEmpty() + edited }
                        .onFailure { logger.error("Could not read messages from {}: {}", file, it.message) }
                }
            }
            return Messages(languages, defaultLanguage)
        }
    }
}
