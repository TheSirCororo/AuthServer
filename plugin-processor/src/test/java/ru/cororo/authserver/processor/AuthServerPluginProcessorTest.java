package ru.cororo.authserver.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiles small Java plugins with the processor and checks the generated descriptor. */
class AuthServerPluginProcessorTest {
    @TempDir
    Path directory;

    private boolean compile(String className, String source, DiagnosticCollector<JavaFileObject> diagnostics) throws Exception {
        Path file = directory.resolve("src/" + className.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        Files.createDirectories(directory.resolve("out"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = compiler.getTask(null, files, diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", directory.resolve("out").toString(), "-proc:full"),
                    null, files.getJavaFileObjects(file));
            task.setProcessors(List.of(new AuthServerPluginProcessor()));
            return task.call();
        }
    }

    @Test
    void generatesDescriptor() throws Exception {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        assertTrue(compile("com.example.Hello", """
                package com.example;
                import ru.cororo.authserver.api.plugin.*;
                @AuthServerPlugin(id = "hello", version = "2.0", description = "Says \\"hi\\"", authors = {"a", "b"},
                        dependencies = {@Dependency(id = "base"), @Dependency(id = "extra", optional = true)})
                public class Hello extends Plugin {}
                """, diagnostics), diagnostics.getDiagnostics().toString());
        var json = Files.readString(directory.resolve("out/authserver-plugin.json"));
        assertEquals(new PluginDescriptor("hello", "hello", "2.0", "com.example.Hello", "Says \"hi\"",
                List.of("a", "b"), List.of("base"), List.of("extra")).toJson(), json);
    }

    @Test
    void rejectsInvalidPlugins() throws Exception {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        assertFalse(compile("com.example.Bad", """
                package com.example;
                import ru.cororo.authserver.api.plugin.*;
                @AuthServerPlugin(id = "Not Valid")
                public class Bad extends Plugin {}
                """, diagnostics));
        assertTrue(diagnostics.getDiagnostics().toString().contains("lowercase"));

        var notPlugin = new DiagnosticCollector<JavaFileObject>();
        assertFalse(compile("com.example.NotPlugin", """
                package com.example;
                @ru.cororo.authserver.api.plugin.AuthServerPlugin(id = "x")
                public class NotPlugin {}
                """, notPlugin));
        assertTrue(notPlugin.getDiagnostics().toString().contains("subclass"));
    }
}
