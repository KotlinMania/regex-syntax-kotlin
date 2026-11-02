package io.github.kotlinmania.build;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;

/** Runs a Java verifier in Gradle's build classloader. */
public abstract class BuildToolTask extends DefaultTask {
    @Input
    public abstract Property<String> getToolClass();

    @Input
    @Optional
    public abstract ListProperty<String> getArguments();

    @Option(option = "args", description = "Arguments for the Java build tool")
    public void setCommandArguments(String value) {
        getArguments().set(splitArguments(value));
    }

    @TaskAction
    public void runTool() {
        invoke(getToolClass().get(), getArguments().getOrElse(List.of()).toArray(String[]::new));
    }

    public static void invoke(String name, String... arguments) {
        if (!name.startsWith("io.github.kotlinmania.build.")) {
            throw new GradleException("Unknown build tool: " + name);
        }
        try {
            Object result = Class.forName(name, true, BuildToolTask.class.getClassLoader())
                    .getMethod("run", String[].class).invoke(null, (Object) arguments);
            if (result instanceof Number number && number.intValue() != 0) {
                throw new GradleException(name + " failed with exit code " + number.intValue());
            }
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error failure) throw failure;
            throw new GradleException(name + " failed: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException error) {
            throw new GradleException("Cannot load Java build tool " + name, error);
        }
    }

    public static void invokeValues(Class<?> type, Object... arguments) {
        invoke(type.getName(), java.util.Arrays.stream(arguments).map(String::valueOf).toArray(String[]::new));
    }

    public static List<String> splitArguments(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else if (c == '\\' && quote == '"' && i + 1 < text.length()
                        && (text.charAt(i + 1) == '"' || text.charAt(i + 1) == '\\')) {
                    value.append(text.charAt(++i));
                } else value.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    result.add(value.toString());
                    value.setLength(0);
                    started = false;
                }
            } else {
                value.append(c);
                started = true;
            }
        }
        if (quote != 0) throw new GradleException("Unterminated quoted build-tool argument");
        if (started) result.add(value.toString());
        return result;
    }
}
