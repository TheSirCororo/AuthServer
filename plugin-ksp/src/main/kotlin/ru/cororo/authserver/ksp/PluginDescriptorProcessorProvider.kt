package ru.cororo.authserver.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import ru.cororo.authserver.processor.PluginDescriptor

/** Registers [PluginDescriptorProcessor] with KSP: `ksp(project(":plugin-ksp"))` in a Kotlin plugin's build. */
class PluginDescriptorProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        PluginDescriptorProcessor(environment.codeGenerator, environment.logger)
}

/**
 * Kotlin counterpart of the Java `AuthServerPluginProcessor`: turns the `@AuthServerPlugin` main class into
 * `authserver-plugin.json` in the generated resources, which end up in the plugin jar.
 */
class PluginDescriptorProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    private var written = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        for (symbol in resolver.getSymbolsWithAnnotation(PluginDescriptor.ANNOTATION)) {
            process(symbol)
        }
        return emptyList()
    }

    private fun process(symbol: KSAnnotated) {
        if (symbol !is KSClassDeclaration || symbol.classKind != ClassKind.CLASS) {
            return logger.error("@AuthServerPlugin can only annotate classes", symbol)
        }
        if (written) return logger.error("Only one class may be annotated with @AuthServerPlugin", symbol)
        val isPlugin = symbol.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == PluginDescriptor.PLUGIN_CLASS }
        if (!isPlugin || Modifier.ABSTRACT in symbol.modifiers) {
            return logger.error("The plugin class must be a concrete subclass of ${PluginDescriptor.PLUGIN_CLASS}", symbol)
        }
        val annotation = symbol.annotations.first { it.qualifiedName() == PluginDescriptor.ANNOTATION }
        val (softDepends, depends) = annotation.list<KSAnnotation>("dependencies")
            .partition { it.argument("optional") as? Boolean ?: false }
        val descriptor = PluginDescriptor(
            annotation.argument("id") as String,
            annotation.argument("name") as? String,
            annotation.argument("version") as? String,
            symbol.binaryName(),
            annotation.argument("description") as? String,
            annotation.list<String>("authors"),
            depends.map { it.argument("id") as String },
            softDepends.map { it.argument("id") as String },
        )
        val problems = descriptor.problems()
        if (problems.isNotEmpty()) return problems.forEach { logger.error(it, symbol) }

        val file = PluginDescriptor.FILE
        val dependencies = Dependencies(aggregating = false, *listOfNotNull(symbol.containingFile).toTypedArray())
        codeGenerator.createNewFileByPath(dependencies, file.substringBeforeLast('.'), file.substringAfterLast('.'))
            .bufferedWriter().use { it.write(descriptor.toJson()) }
        written = true
    }

    private fun KSAnnotation.qualifiedName() = annotationType.resolve().declaration.qualifiedName?.asString()

    /** The argument's value, falling back to the declared default when it was omitted. */
    private fun KSAnnotation.argument(name: String): Any? =
        arguments.firstOrNull { it.name?.asString() == name }?.value
            ?: defaultArguments.firstOrNull { it.name?.asString() == name }?.value

    @Suppress("UNCHECKED_CAST")
    private fun <T> KSAnnotation.list(name: String): List<T> = when (val value = argument(name)) {
        is List<*> -> value as List<T>
        is Array<*> -> value.toList() as List<T>
        else -> emptyList()
    }

    /** `com.example.Outer$Inner`, the name `Class.forName` expects. */
    private fun KSClassDeclaration.binaryName(): String {
        val parent = parentDeclaration as? KSClassDeclaration ?: return qualifiedName!!.asString()
        return parent.binaryName() + "$" + simpleName.asString()
    }
}
