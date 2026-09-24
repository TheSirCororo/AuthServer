package ru.cororo.authserver.processor;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates {@code authserver-plugin.json} from the {@code @AuthServerPlugin} main class of a Java plugin.
 * Add the {@code plugin-processor} artifact to {@code annotationProcessor}. Annotation values are read through
 * mirrors, so the processor itself needs nothing but the JDK.
 */
@SupportedAnnotationTypes(PluginDescriptor.ANNOTATION)
public final class AuthServerPluginProcessor extends AbstractProcessor {
    private boolean written;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (TypeElement annotation : annotations) {
            for (Element element : round.getElementsAnnotatedWith(annotation)) {
                process(element, annotation);
            }
        }
        return false;
    }

    private void process(Element element, TypeElement annotationType) {
        var messager = processingEnv.getMessager();
        if (element.getKind() != ElementKind.CLASS || !(element instanceof TypeElement type)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "@AuthServerPlugin can only annotate classes", element);
            return;
        }
        if (written) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Only one class may be annotated with @AuthServerPlugin", element);
            return;
        }
        if (!extendsPlugin(type.asType()) || type.getModifiers().contains(Modifier.ABSTRACT)) {
            messager.printMessage(Diagnostic.Kind.ERROR, "The plugin class must be a concrete subclass of " + PluginDescriptor.PLUGIN_CLASS, element);
            return;
        }
        AnnotationMirror mirror = element.getAnnotationMirrors().stream()
                .filter(it -> it.getAnnotationType().asElement().equals(annotationType)).findFirst().orElseThrow();
        Map<? extends ExecutableElement, ? extends AnnotationValue> values = processingEnv.getElementUtils().getElementValuesWithDefaults(mirror);
        var depends = new ArrayList<String>();
        var softDepends = new ArrayList<String>();
        for (var dependency : list(value(values, "dependencies"))) {
            var dependencyValues = processingEnv.getElementUtils().getElementValuesWithDefaults((AnnotationMirror) dependency.getValue());
            var id = (String) value(dependencyValues, "id").getValue();
            (Boolean.TRUE.equals(value(dependencyValues, "optional").getValue()) ? softDepends : depends).add(id);
        }
        var descriptor = new PluginDescriptor(
                (String) value(values, "id").getValue(),
                (String) value(values, "name").getValue(),
                (String) value(values, "version").getValue(),
                processingEnv.getElementUtils().getBinaryName(type).toString(),
                (String) value(values, "description").getValue(),
                list(value(values, "authors")).stream().map(it -> (String) it.getValue()).toList(),
                depends,
                softDepends);
        var problems = descriptor.problems();
        if (!problems.isEmpty()) {
            problems.forEach(problem -> messager.printMessage(Diagnostic.Kind.ERROR, problem, element));
            return;
        }
        try (Writer writer = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", PluginDescriptor.FILE, element).openWriter()) {
            writer.write(descriptor.toJson());
            written = true;
        } catch (IOException exception) {
            messager.printMessage(Diagnostic.Kind.ERROR, "Could not write " + PluginDescriptor.FILE + ": " + exception.getMessage(), element);
        }
    }

    private boolean extendsPlugin(TypeMirror type) {
        for (TypeMirror current = type; current instanceof DeclaredType declared; ) {
            var element = (TypeElement) declared.asElement();
            if (element.getQualifiedName().contentEquals(PluginDescriptor.PLUGIN_CLASS)) return true;
            current = element.getSuperclass();
        }
        return false;
    }

    private static AnnotationValue value(Map<? extends ExecutableElement, ? extends AnnotationValue> values, String name) {
        return values.entrySet().stream().filter(it -> it.getKey().getSimpleName().contentEquals(name))
                .map(Map.Entry::getValue).findFirst().orElseThrow(() -> new IllegalStateException("No attribute " + name));
    }

    @SuppressWarnings("unchecked")
    private static List<? extends AnnotationValue> list(AnnotationValue value) {
        return (List<? extends AnnotationValue>) value.getValue();
    }
}
