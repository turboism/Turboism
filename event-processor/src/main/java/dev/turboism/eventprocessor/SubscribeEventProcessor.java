package dev.turboism.eventprocessor;

import dev.turboism.sdk.event.GeneratedSubscriberCatalog;
import dev.turboism.sdk.event.SubscribeEvent;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Generated;
import javax.annotation.processing.Messager;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Generates direct, deterministic subscriber catalogs for {@link SubscribeEvent}. */
public final class SubscribeEventProcessor extends AbstractProcessor {

    private static final String EVENT_ROOT = "dev.turboism.sdk.event.EventBus.TurboismEvent";
    private static final String SERVICE = GeneratedSubscriberCatalog.class.getName();

    private final Set<String> generatedCatalogs = new TreeSet<>();
    private boolean serviceWritten;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(SubscribeEvent.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_17;
    }

    @Override
    public boolean process(
        final Set<? extends TypeElement> annotations,
        final RoundEnvironment round
    ) {
        final Map<TypeElement, List<ExecutableElement>> subscribers = new LinkedHashMap<>();
        for (Element root : round.getRootElements()) {
            if (!(root instanceof TypeElement owner)
                || owner.getKind() != ElementKind.CLASS
                || !owner.getModifiers().contains(Modifier.PUBLIC)
                || owner.getModifiers().contains(Modifier.ABSTRACT)) {
                continue;
            }
            final List<ExecutableElement> methods = processingEnv.getElementUtils()
                .getAllMembers(owner).stream()
                .filter(element -> element.getKind() == ElementKind.METHOD)
                .map(ExecutableElement.class::cast)
                .filter(method -> method.getAnnotation(SubscribeEvent.class) != null)
                .filter(method -> validate(method, owner))
                .toList();
            if (!methods.isEmpty()) {
                subscribers.put(owner, methods);
            }
        }
        subscribers.forEach(this::generate);
        if (round.processingOver() && !serviceWritten) {
            writeServiceFile();
            serviceWritten = true;
        }
        return false;
    }

    private boolean validate(final ExecutableElement method, final TypeElement owner) {
        boolean valid = true;
        if (!owner.getModifiers().contains(Modifier.PUBLIC)
            || owner.getModifiers().contains(Modifier.ABSTRACT)) {
            error(method, "subscriber owner must be a public concrete class");
            valid = false;
        }
        if (!method.getModifiers().contains(Modifier.PUBLIC)
            || method.getModifiers().contains(Modifier.STATIC)) {
            error(method, "subscriber must be a public instance method");
            valid = false;
        }
        if (method.getReturnType().getKind() != TypeKind.VOID) {
            error(method, "subscriber must return void");
            valid = false;
        }
        if (method.getParameters().size() != 1) {
            error(method, "subscriber must declare exactly one event parameter");
            return false;
        }
        final TypeElement eventRoot = processingEnv.getElementUtils().getTypeElement(EVENT_ROOT);
        final TypeMirror parameter = method.getParameters().get(0).asType();
        if (eventRoot == null
            || !processingEnv.getTypeUtils().isAssignable(parameter, eventRoot.asType())) {
            error(method, "subscriber parameter must implement TurboismEvent");
            valid = false;
        }
        return valid;
    }

    private void generate(
        final TypeElement owner,
        final List<ExecutableElement> discovered
    ) {
        final List<ExecutableElement> methods = discovered.stream()
            .sorted(Comparator.comparing(this::signature))
            .toList();
        final PackageElement ownerPackage = processingEnv.getElementUtils().getPackageOf(owner);
        final String packageName = ownerPackage.getQualifiedName().toString();
        final String ownerName = owner.getQualifiedName().toString();
        final String simpleOwner = ownerName.substring(packageName.isEmpty() ? 0 : packageName.length() + 1)
            .replace('.', '_');
        final String simpleCatalog = simpleOwner + "__TurboismSubscriberCatalog";
        final String catalogName = packageName.isEmpty()
            ? simpleCatalog
            : packageName + "." + simpleCatalog;
        if (!generatedCatalogs.add(catalogName)) {
            return;
        }
        try {
            final JavaFileObject source = processingEnv.getFiler().createSourceFile(catalogName, owner);
            try (Writer writer = source.openWriter()) {
                if (!packageName.isEmpty()) {
                    writer.write("package " + packageName + ";\n\n");
                }
                writer.write("@" + Generated.class.getName() + "(\""
                    + SubscribeEventProcessor.class.getName() + "\")\n");
                writer.write("public final class " + simpleCatalog + " implements "
                    + GeneratedSubscriberCatalog.class.getName() + " {\n");
                writer.write("    @Override public Class<?> entrypointType() { return "
                    + ownerName + ".class; }\n\n");
                writer.write("    @Override public void register(Object entrypoint, "
                    + "dev.turboism.sdk.event.EventSubscriberRegistrar registrar) {\n");
                writer.write("        " + ownerName + " target = (" + ownerName + ") entrypoint;\n");
                for (int ordinal = 0; ordinal < methods.size(); ordinal++) {
                    final ExecutableElement method = methods.get(ordinal);
                    final String eventType = method.getParameters().get(0).asType().toString();
                    final SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
                    writer.write("        registrar.register(" + eventType + ".class, "
                        + "dev.turboism.sdk.event.EventPriority." + annotation.priority().name()
                        + ", " + ordinal + ", \"" + escape(signature(method)) + "\", "
                        + "target::" + method.getSimpleName() + ");\n");
                }
                writer.write("    }\n}\n");
            }
        } catch (IOException failure) {
            error(owner, "could not generate subscriber catalog: " + failure.getMessage());
        }
    }

    private String signature(final ExecutableElement method) {
        final TypeElement declaringType = (TypeElement) method.getEnclosingElement();
        return declaringType.getQualifiedName() + "#" + method.getSimpleName()
            + method.getParameters().stream()
                .map(parameter -> parameter.asType().toString())
                .collect(java.util.stream.Collectors.joining(",", "(", ")"))
            + ":" + method.getReturnType();
    }

    private void writeServiceFile() {
        if (generatedCatalogs.isEmpty()) {
            return;
        }
        final Filer filer = processingEnv.getFiler();
        try {
            final var resource = filer.createResource(
                StandardLocation.CLASS_OUTPUT,
                "",
                "META-INF/services/" + SERVICE
            );
            try (Writer writer = resource.openWriter()) {
                for (String catalog : generatedCatalogs) {
                    writer.write(catalog);
                    writer.write('\n');
                }
            }
        } catch (IOException failure) {
            processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "could not generate subscriber service catalog: " + failure.getMessage()
            );
        }
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void error(final Element element, final String message) {
        final Messager messager = processingEnv.getMessager();
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
