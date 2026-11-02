package io.github.kotlinmania.build;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Builds the repository's N-API C/C++ bindings directly, without a script-language build generator. */
public final class NodeFfiBuilder {
    private static final String ADDON_INCLUDE = "<!@(node -p \"require('node-addon-api').include\")";
    private static final String ADDON_DEPENDENCY = "<!(node -p \"require('node-addon-api').gyp\")";
    private static final Set<String> LIST_OPTIONS = Set.of(
            "sources", "include_dirs", "dependencies", "defines", "cflags", "cflags_c", "cflags_cc", "ldflags", "libraries");
    private static final Set<String> SCALAR_OPTIONS = Set.of("target_name", "type", "msbuild_toolset");
    private static final Set<String> XCODE_OPTIONS = Set.of("GCC_ENABLE_CPP_EXCEPTIONS", "CLANG_CXX_LIBRARY",
            "CLANG_CXX_LANGUAGE_STANDARD", "MACOSX_DEPLOYMENT_TARGET", "OTHER_CFLAGS", "OTHER_CPLUSPLUSFLAGS", "OTHER_LDFLAGS");
    private static final Pattern CONDITION = Pattern.compile("\\s*(OS|target_arch)\\s*(==|!=)\\s*(['\"])([A-Za-z0-9_]+)\\3\\s*");
    private static final Pattern VERSION = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");
    private static final int MAX_DOWNLOAD = 64 * 1024 * 1024;
    private static final long MAX_EXTRACTED = 256L * 1024 * 1024;

    private NodeFfiBuilder() {}

    private record Options(Path project, Path addon, Path build, String node, String version,
                           Path headers, String cc, String cxx, String linker, boolean offline) {}

    /** Resolve only the explicitly supported binding settings; unsupported settings always fail. */
    public static List<Map<String, Object>> resolveTargets(Object specification, String platform, String architecture) {
        String os = switch (platform) {
            case "darwin", "mac" -> "mac";
            case "linux" -> "linux";
            case "win32", "win" -> "win";
            default -> throw new IllegalArgumentException("Unsupported Node platform: " + platform);
        };
        Map<String, Object> root = object(specification, "binding.gyp");
        for (String key : root.keySet()) {
            if (!Set.of("targets", "target_defaults").contains(key)) {
                throw new IllegalArgumentException("Unsupported binding.gyp root setting: " + key);
            }
        }
        Map<String, Object> defaults = object(root.getOrDefault("target_defaults", Map.of()), "target_defaults");
        validateSettings(defaults);
        if (!(root.get("targets") instanceof List<?> targets) || targets.isEmpty()) {
            throw new IllegalArgumentException("binding.gyp must declare at least one target");
        }
        List<Map<String, Object>> resolved = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (Object entry : targets) {
            Map<String, Object> target = object(entry, "target");
            validateSettings(target);
            Map<String, Object> configuration = new LinkedHashMap<>();
            merge(configuration, defaults, os, architecture);
            merge(configuration, target, os, architecture);
            String name = string(configuration.get("target_name"), "target_name");
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !names.add(name)) {
                throw new IllegalArgumentException("Invalid or duplicate native target name: " + name);
            }
            if (!"loadable_module".equals(configuration.getOrDefault("type", "loadable_module"))) {
                throw new IllegalArgumentException("Only loadable_module N-API targets are supported: " + name);
            }
            if (strings(configuration.getOrDefault("sources", List.of()), "sources").isEmpty()) {
                throw new IllegalArgumentException("Native target has no sources: " + name);
            }
            for (String dependency : strings(configuration.getOrDefault("dependencies", List.of()), "dependencies")) {
                if (!dependency.equals(ADDON_DEPENDENCY)) {
                    throw new IllegalArgumentException("Unsupported native target dependency: " + dependency);
                }
            }
            resolved.add(configuration);
        }
        return resolved;
    }

    private static void validateSettings(Map<String, Object> settings) {
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            String base = key.endsWith("!") ? key.substring(0, key.length() - 1) : key;
            if (LIST_OPTIONS.contains(base)) {
                for (String value : strings(entry.getValue(), key)) {
                    if (!(base.equals("include_dirs") && value.equals(ADDON_INCLUDE))
                            && !(base.equals("dependencies") && value.equals(ADDON_DEPENDENCY))) {
                        literal(value);
                    }
                }
            } else if (SCALAR_OPTIONS.contains(key)) {
                string(entry.getValue(), key);
            } else if (key.equals("xcode_settings")) {
                Map<String, Object> values = object(entry.getValue(), key);
                for (String setting : values.keySet()) {
                    if (!XCODE_OPTIONS.contains(setting)) {
                        throw new IllegalArgumentException("Unsupported Xcode setting: " + setting);
                    }
                    if (setting.startsWith("OTHER_")) {
                        strings(values.get(setting), setting);
                    } else {
                        string(values.get(setting), setting);
                    }
                }
            } else if (key.equals("msvs_settings")) {
                Map<String, Object> values = object(entry.getValue(), key);
                if (!values.keySet().equals(Set.of("VCCLCompilerTool"))) {
                    throw new IllegalArgumentException("Unsupported MSVC settings: " + values.keySet());
                }
                Map<String, Object> compiler = object(values.get("VCCLCompilerTool"), "VCCLCompilerTool");
                if (!compiler.keySet().equals(Set.of("ExceptionHandling"))
                        || !Set.of("0", "1", "2").contains(String.valueOf(compiler.get("ExceptionHandling")))) {
                    throw new IllegalArgumentException("Unsupported MSVC compiler settings: " + compiler);
                }
            } else if (key.equals("link_settings")) {
                Map<String, Object> link = object(entry.getValue(), key);
                for (String setting : link.keySet()) {
                    if (!Set.of("libraries", "ldflags").contains(setting)) {
                        throw new IllegalArgumentException("Unsupported link setting: " + setting);
                    }
                    strings(link.get(setting), setting);
                }
            } else if (key.equals("conditions")) {
                if (!(entry.getValue() instanceof List<?> conditions)) {
                    throw new IllegalArgumentException("conditions must be a list");
                }
                for (Object condition : conditions) {
                    if (!(condition instanceof List<?> parts) || parts.size() < 2 || parts.size() > 3
                            || !(parts.getFirst() instanceof String expression) || !CONDITION.matcher(expression).matches()) {
                        throw new IllegalArgumentException("Unsupported binding condition: " + condition);
                    }
                    for (int index = 1; index < parts.size(); index++) {
                        validateSettings(object(parts.get(index), "condition branch"));
                    }
                }
            } else {
                throw new IllegalArgumentException("Unsupported binding target setting: " + key);
            }
        }
    }

    private static void merge(Map<String, Object> destination, Map<String, Object> source, String os, String arch) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.equals("conditions")) {
                continue;
            }
            if (key.equals("link_settings")) {
                merge(destination, object(value, key), os, arch);
            } else if (value instanceof List<?>) {
                List<String> combined = new ArrayList<>(strings(destination.getOrDefault(key, List.of()), key));
                combined.addAll(strings(value, key));
                destination.put(key, combined);
            } else if (value instanceof Map<?, ?>) {
                Map<String, Object> combined = new LinkedHashMap<>(object(destination.getOrDefault(key, Map.of()), key));
                for (Map.Entry<String, Object> nested : object(value, key).entrySet()) {
                    if (nested.getValue() instanceof Map<?, ?>) {
                        Map<String, Object> nestedCombined = new LinkedHashMap<>(object(combined.getOrDefault(nested.getKey(), Map.of()), key));
                        nestedCombined.putAll(object(nested.getValue(), key));
                        combined.put(nested.getKey(), nestedCombined);
                    } else {
                        combined.put(nested.getKey(), nested.getValue());
                    }
                }
                destination.put(key, combined);
            } else {
                destination.put(key, value);
            }
        }
        if (source.get("conditions") instanceof List<?> conditions) {
            for (Object condition : conditions) {
                List<?> parts = (List<?>) condition;
                Matcher match = CONDITION.matcher((String) parts.getFirst());
                if (!match.matches()) {
                    throw new IllegalArgumentException("Unsupported condition: " + parts.getFirst());
                }
                boolean equal = (match.group(1).equals("OS") ? os : arch).equals(match.group(4));
                boolean selected = match.group(2).equals("==") == equal;
                if (selected || parts.size() == 3) {
                    merge(destination, object(parts.get(selected ? 1 : 2), "condition"), os, arch);
                }
            }
        }
    }

    public static int run(String... arguments) {
        if (List.of(arguments).contains("--help")) {
            System.out.println("NodeFfiBuilder --project-dir DIR --addon-dir DIR --build-dir DIR"
                    + " [--node EXECUTABLE] [--node-version VERSION] [--headers-dir DIR]"
                    + " [--cc EXECUTABLE] [--cxx EXECUTABLE] [--linker EXECUTABLE] [--offline]");
            return 0;
        }
        Path temporary = null;
        Path report = null;
        Map<String, Object> receipt = new LinkedHashMap<>();
        try {
            Options options = parse(arguments);
            Path project = options.project().toRealPath();
            Path addon = options.addon().toRealPath();
            if (!addon.startsWith(project) || !options.build().startsWith(project)) {
                throw new IllegalArgumentException("Addon sources and build storage must be inside the owning project");
            }
            Path scratch = options.build().resolve("tmp/node-ffi");
            Files.createDirectories(scratch);
            if (!scratch.toRealPath().startsWith(project)) {
                throw new IllegalArgumentException("Build storage resolves outside the owning project");
            }
            temporary = Files.createTempDirectory(scratch, "compile-");
            report = options.build().resolve("reports/node-ffi/" + addon.getFileName() + ".json");
            Map<String, Object> runtime = object(Json.parse(execute(List.of(options.node(), "-p",
                    "JSON.stringify({version:process.versions.node,platform:process.platform,arch:process.arch,executable:process.execPath})"),
                    addon, temporary)), "Node runtime");
            String version = exactVersion(string(runtime.get("version"), "Node version"));
            if (options.version() != null && !exactVersion(options.version()).equals(version)) {
                throw new IllegalArgumentException("Node " + version + " does not match the required version " + options.version());
            }
            String platform = string(runtime.get("platform"), "Node platform");
            String arch = string(runtime.get("arch"), "Node architecture");
            if (!Set.of("arm64", "x64").contains(arch)) {
                throw new IllegalArgumentException("Unsupported Node architecture: " + arch);
            }
            receipt.put("node", runtime);
            receipt.put("addon", addon.toString());
            List<Map<String, Object>> targets = resolveTargets(Json.read(addon.resolve("binding.gyp")), platform, arch);
            String apiPath = execute(List.of(options.node(), "-p", "require.resolve('node-addon-api/package.json')"), addon, temporary).strip();
            Path api = Path.of(apiPath).toRealPath().getParent();
            if (!Files.isRegularFile(api.resolve("napi.h"))) {
                throw new IllegalArgumentException("Installed node-addon-api headers are missing: " + api);
            }
            receipt.put("nodeAddonApi", Json.read(api.resolve("package.json")));
            Path headers = headers(options, runtime, version, temporary, receipt);
            Path nodeLibrary = platform.equals("win32") ? nodeLibrary(options, version, arch, temporary, receipt) : null;
            List<Map<String, Object>> built = new ArrayList<>();
            for (Map<String, Object> target : targets) {
                Path output = compile(options, project, addon, target, platform, arch, headers, api, nodeLibrary, temporary);
                built.add(Map.of("target", target.get("target_name"), "binary", output.toString(), "sha256", sha256(output)));
            }
            receipt.put("targets", built);
            receipt.put("passed", true);
            System.out.println("Built " + built.size() + " N-API target(s) for Node " + version + " " + platform + "/" + arch);
            return 0;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            receipt.put("passed", false);
            receipt.put("error", exception.getMessage() == null ? exception.toString() : exception.getMessage());
            System.err.println("N-API build failed: " + receipt.get("error"));
            return 1;
        } finally {
            try {
                if (temporary != null) {
                    deleteTree(temporary);
                }
                if (report != null) {
                    Json.write(report, receipt);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not clean or report the project-local N-API build", exception);
            }
        }
    }

    public static void main(String[] arguments) {
        System.exit(run(arguments));
    }

    private static Path compile(Options options, Path project, Path addon, Map<String, Object> target, String platform,
                                String arch, Path headers, Path api, Path nodeLibrary, Path temporary)
            throws IOException, InterruptedException {
        boolean windows = platform.equals("win32");
        boolean mac = platform.equals("darwin");
        String name = (String) target.get("target_name");
        String product = !windows && name.startsWith("lib") ? name.substring(3) : name;
        if (product.isEmpty()) {
            throw new IllegalArgumentException("Native target produces an empty output name: " + name);
        }
        Path output = addon.resolve("build/Release/" + product + ".node");
        Files.createDirectories(output.getParent());
        if (!output.getParent().toRealPath().startsWith(project)) {
            throw new IllegalArgumentException("Native output resolves outside the owning project");
        }
        Files.deleteIfExists(output);
        Map<String, Object> xcode = object(target.getOrDefault("xcode_settings", Map.of()), "xcode_settings");
        List<String> includes = new ArrayList<>(List.of(headers.toString(), api.toString()));
        for (String include : option(target, "include_dirs")) {
            if (!include.equals(ADDON_INCLUDE)) {
                includes.add(resolveInput(addon, include).toString());
            }
        }
        List<String> defines = new ArrayList<>(List.of("NODE_GYP_MODULE_NAME=" + name, "BUILDING_NODE_EXTENSION",
                "USING_UV_SHARED=1", "USING_V8_SHARED=1", "V8_DEPRECATION_WARNINGS=1", "_LARGEFILE_SOURCE",
                "_FILE_OFFSET_BITS=64", "__STDC_FORMAT_MACROS", "NDEBUG"));
        defines.addAll(option(target, "defines"));
        defines.removeAll(strings(target.getOrDefault("defines!", List.of()), "defines!"));
        String toolset = String.valueOf(target.getOrDefault("msbuild_toolset", ""));
        if (!toolset.isEmpty() && !toolset.equals("ClangCL")) {
            throw new IllegalArgumentException("Unsupported Windows toolset: " + toolset);
        }
        String cc = options.cc() != null ? options.cc() : environmentCompiler("CC", windows ? (toolset.isEmpty() ? "cl.exe" : "clang-cl.exe") : "cc");
        String cxx = options.cxx() != null ? options.cxx() : environmentCompiler("CXX", windows ? (toolset.isEmpty() ? "cl.exe" : "clang-cl.exe") : "c++");
        List<Path> objects = new ArrayList<>();
        for (String source : option(target, "sources")) {
            Path input = resolveInput(addon, source).toRealPath();
            if (!input.startsWith(project) || !Files.isRegularFile(input)) {
                throw new IllegalArgumentException("Native source must be a file inside the owning project: " + input);
            }
            boolean cpp = source.endsWith(".cc") || source.endsWith(".cpp") || source.endsWith(".cxx");
            if (!cpp && !source.endsWith(".c")) {
                throw new IllegalArgumentException("Unsupported native source language: " + source);
            }
            Path object = temporary.resolve(name + "-" + objects.size() + (windows ? ".obj" : ".o"));
            List<String> command = new ArrayList<>(List.of(cpp ? cxx : cc));
            if (windows) {
                command.addAll(List.of("/nologo", "/c", "/O2", "/MD", "/W3", cpp ? "/TP" : "/TC"));
                if (cpp) {
                    command.add("/std:c++20");
                    String exceptions = "1";
                    if (target.containsKey("msvs_settings")) {
                        exceptions = String.valueOf(object(object(target.get("msvs_settings"), "msvs_settings")
                                .get("VCCLCompilerTool"), "VCCLCompilerTool").get("ExceptionHandling"));
                    }
                    command.add(exceptions.equals("2") ? "/EHa" : exceptions.equals("1") ? "/EHsc" : "/EHs-c-");
                }
                for (String include : includes) command.add("/I" + include);
                for (String define : defines) command.add("/D" + literal(define));
                addWindowsFlags(command, option(target, "cflags"));
                addWindowsFlags(command, option(target, cpp ? "cflags_cc" : "cflags_c"));
                command.addAll(List.of(input.toString(), "/Fo" + object));
            } else {
                List<String> flags = new ArrayList<>(List.of("-O3", "-fPIC", "-pthread"));
                if (cpp) flags.addAll(List.of("-std=gnu++20", "-fno-rtti", "-fno-exceptions"));
                flags.addAll(option(target, "cflags"));
                flags.addAll(option(target, cpp ? "cflags_cc" : "cflags_c"));
                flags.removeAll(strings(target.getOrDefault("cflags!", List.of()), "cflags!"));
                flags.removeAll(strings(target.getOrDefault(cpp ? "cflags_cc!" : "cflags_c!", List.of()), "language flags!"));
                if (mac) {
                    flags.addAll(List.of("-arch", arch.equals("arm64") ? "arm64" : "x86_64"));
                    addMacFlags(flags, xcode, cpp, false);
                }
                flags.forEach(flag -> command.add(literal(flag)));
                for (String include : includes) command.addAll(List.of("-I", include));
                for (String define : defines) command.add("-D" + literal(define));
                command.addAll(List.of("-c", input.toString(), "-o", object.toString()));
            }
            execute(command, addon, temporary);
            objects.add(object);
        }
        List<String> link = new ArrayList<>();
        if (windows) {
            link.add(options.linker() == null ? (toolset.isEmpty() ? "link.exe" : "lld-link.exe") : options.linker());
            link.addAll(List.of("/NOLOGO", "/DLL", "/MACHINE:" + (arch.equals("arm64") ? "ARM64" : "X64"), "/OUT:" + output));
            objects.forEach(object -> link.add(object.toString()));
            link.add(nodeLibrary.toString());
            link.addAll(List.of("kernel32.lib", "user32.lib", "gdi32.lib", "winspool.lib", "comdlg32.lib", "advapi32.lib",
                    "shell32.lib", "ole32.lib", "oleaut32.lib", "uuid.lib", "odbc32.lib", "DelayImp.lib"));
        } else {
            link.add(options.linker() == null ? cxx : options.linker());
            link.addAll(mac ? List.of("-bundle", "-undefined", "dynamic_lookup", "-arch", arch.equals("arm64") ? "arm64" : "x86_64")
                    : List.of("-shared", "-pthread"));
            if (mac) addMacFlags(link, xcode, true, true);
            objects.forEach(object -> link.add(object.toString()));
            link.addAll(List.of("-o", output.toString()));
        }
        for (String flag : option(target, "ldflags")) link.add(literal(flag));
        for (String library : option(target, "libraries")) {
            if (library.startsWith("-framework ")) {
                if (!mac || !library.substring(11).matches("[A-Za-z0-9_]+")) {
                    throw new IllegalArgumentException("Invalid framework library: " + library);
                }
                link.addAll(List.of("-framework", library.substring(11)));
            } else if (library.startsWith("-")) {
                link.add(literal(library));
            } else if (library.contains("/") || library.contains("\\")) {
                link.add(resolveInput(addon, library).toString());
            } else {
                link.add(literal(library));
            }
        }
        execute(link, addon, temporary);
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("Native compiler did not produce " + output);
        }
        return output;
    }

    private static void addMacFlags(List<String> flags, Map<String, Object> xcode, boolean cpp, boolean link) {
        String minimum = String.valueOf(xcode.getOrDefault("MACOSX_DEPLOYMENT_TARGET", "11.0"));
        if (!minimum.matches("[0-9]+(?:\\.[0-9]+){1,2}")) {
            throw new IllegalArgumentException("Invalid macOS deployment target: " + minimum);
        }
        flags.add("-mmacosx-version-min=" + minimum);
        if (cpp) {
            if (xcode.containsKey("CLANG_CXX_LIBRARY")) flags.add("-stdlib=" + literal((String) xcode.get("CLANG_CXX_LIBRARY")));
            if (!link) {
                if (xcode.containsKey("CLANG_CXX_LANGUAGE_STANDARD")) flags.add("-std=" + literal((String) xcode.get("CLANG_CXX_LANGUAGE_STANDARD")));
                if (xcode.containsKey("GCC_ENABLE_CPP_EXCEPTIONS")) {
                    String value = (String) xcode.get("GCC_ENABLE_CPP_EXCEPTIONS");
                    if (!Set.of("YES", "NO").contains(value)) throw new IllegalArgumentException("Unsupported exception setting: " + value);
                    flags.add(value.equals("YES") ? "-fexceptions" : "-fno-exceptions");
                }
            }
        }
        flags.addAll(strings(xcode.getOrDefault(link ? "OTHER_LDFLAGS" : "OTHER_CFLAGS", List.of()), "Xcode flags"));
        if (cpp && !link) flags.addAll(strings(xcode.getOrDefault("OTHER_CPLUSPLUSFLAGS", List.of()), "C++ flags"));
    }

    private static void addWindowsFlags(List<String> command, List<String> flags) {
        for (String flag : flags) {
            if (flag.startsWith("-std=c++") || flag.startsWith("-std=gnu++")) {
                command.add("/std:c++" + flag.substring(flag.indexOf("++") + 2));
            } else if (flag.equals("-fno-exceptions")) {
                command.add("/EHs-c-");
            } else if (flag.equals("-fexceptions")) {
                command.add("/EHsc");
            } else if (flag.startsWith("/")) {
                command.add(literal(flag));
            } else {
                throw new IllegalArgumentException("Unsupported Windows compiler flag: " + flag);
            }
        }
    }

    private static Path headers(Options options, Map<String, Object> runtime, String version, Path temporary,
                                Map<String, Object> receipt) throws IOException, InterruptedException {
        if (options.headers() != null) {
            verifyHeaderVersion(options.headers(), version);
            receipt.put("headers", options.headers().toString());
            receipt.put("headerSource", "explicit-existing");
            return options.headers();
        }
        Path executable = Path.of((String) runtime.get("executable")).toRealPath();
        Path installed = executable.getParent().getParent().resolve("include/node");
        if (Files.isRegularFile(installed.resolve("node_version.h"))) {
            try {
                verifyHeaderVersion(installed, version);
                receipt.put("headers", installed.toString());
                receipt.put("headerSource", "matching-installed-node");
                return installed;
            } catch (IllegalArgumentException ignored) {
                // A different system Node version must not supply this runtime's headers.
            }
        }
        Path destination = releaseHeaders(options.build(), version, temporary, receipt, options.offline());
        receipt.put("headers", destination.toString());
        receipt.put("headerSource", "verified-node-release");
        return destination;
    }

    /** Download the exact official release headers into a caller-provided project build directory. */
    public static Path acquireReleaseHeaders(Path buildDirectory, String version) throws IOException, InterruptedException {
        Path build = buildDirectory.toAbsolutePath().normalize();
        Path scratch = build.resolve("tmp/node-ffi");
        Files.createDirectories(scratch);
        Path temporary = Files.createTempDirectory(scratch, "headers-");
        try {
            return releaseHeaders(build, exactVersion(version), temporary, new LinkedHashMap<>(), false);
        } finally {
            deleteTree(temporary);
        }
    }

    private static Path releaseHeaders(Path build, String version, Path temporary, Map<String, Object> receipt, boolean offline)
            throws IOException, InterruptedException {
        Path cache = build.resolve("node-ffi/node-v" + version);
        Files.createDirectories(cache);
        String filename = "node-v" + version + "-headers.tar.gz";
        Path archive = verifiedDownload(version, filename, cache.resolve(filename), temporary, receipt, offline);
        Path destination = cache.resolve("headers");
        if (Files.exists(destination)) deleteTree(destination);
        Files.createDirectories(destination);
        extractHeaders(archive, destination, "node-v" + version + "/include/node/");
        verifyHeaderVersion(destination, version);
        return destination;
    }

    private static Path nodeLibrary(Options options, String version, String architecture, Path temporary,
                                     Map<String, Object> receipt) throws IOException, InterruptedException {
        Path destination = options.build().resolve("node-ffi/node-v" + version + "/win-" + architecture + "/node.lib");
        Files.createDirectories(destination.getParent());
        return verifiedDownload(version, "win-" + architecture + "/node.lib", destination, temporary, receipt, options.offline());
    }

    private static Path verifiedDownload(String version, String filename, Path destination, Path temporary,
                                          Map<String, Object> receipt, boolean offline) throws IOException, InterruptedException {
        URI base = URI.create("https://nodejs.org/download/release/v" + version + "/");
        Path manifest = destination.getParent().resolve("SHASUMS256.txt");
        if (!Files.isRegularFile(manifest)) {
            if (offline) throw new IOException("Offline N-API build has no cached Node release checksum manifest: " + manifest);
            download(base.resolve("SHASUMS256.txt"), manifest, temporary);
        }
        String expected = checksum(Files.readString(manifest), filename);
        if (!Files.isRegularFile(destination) || !sha256(destination).equals(expected)) {
            if (offline) throw new IOException("Offline N-API build has no verified cached Node release file: " + filename);
            Files.deleteIfExists(destination);
            download(base.resolve(filename), destination, temporary);
        }
        if (!sha256(destination).equals(expected)) {
            Files.deleteIfExists(destination);
            throw new IOException("Node release checksum mismatch: " + filename);
        }
        receipt.put(filename + "Sha256", expected);
        return destination;
    }

    public static String checksum(String manifest, String filename) {
        String found = null;
        for (String line : manifest.lines().toList()) {
            Matcher matcher = Pattern.compile("^([0-9a-fA-F]{64})\\s+\\*?(.+)$").matcher(line);
            if (matcher.matches() && matcher.group(2).equals(filename)) {
                if (found != null) throw new IllegalArgumentException("Duplicate Node release checksum: " + filename);
                found = matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        if (found == null) throw new IllegalArgumentException("Node release has no checksum for " + filename);
        return found;
    }

    private static void download(URI uri, Path destination, Path temporary) throws IOException, InterruptedException {
        Path partial = Files.createTempFile(temporary, "node-release-", ".download");
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(partial)) {
                if (response.statusCode() != 200) throw new IOException("Node release download returned HTTP " + response.statusCode() + ": " + uri);
                copyLimited(input, output, MAX_DOWNLOAD);
            }
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** Extract only Node header files, checking tar integrity and refusing links or path traversal. */
    public static void extractHeaders(Path archive, Path destination, String prefix) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long extracted = 0;
        String longName = null;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            while (true) {
                byte[] header = input.readNBytes(512);
                if (header.length != 512) throw new IOException("Truncated Node header archive");
                boolean empty = true;
                for (byte value : header) empty &= value == 0;
                if (empty) break;
                long sum = 0;
                for (int index = 0; index < header.length; index++) sum += index >= 148 && index < 156 ? 32 : Byte.toUnsignedInt(header[index]);
                if (sum != octal(header, 148, 8)) throw new IOException("Invalid Node header archive checksum");
                String name = tarString(header, 0, 100);
                String directory = tarString(header, 345, 155);
                if (!directory.isEmpty()) name = directory + "/" + name;
                if (longName != null) { name = longName; longName = null; }
                long size = octal(header, 124, 12);
                extracted += size;
                if (size < 0 || extracted > MAX_EXTRACTED) throw new IOException("Node header archive exceeds the extraction limit");
                int type = header[156];
                if (type == 'L') {
                    if (size > 8192) throw new IOException("Oversized Node archive filename");
                    byte[] content = input.readNBytes((int) size);
                    if (content.length != size) throw new IOException("Truncated Node archive filename");
                    longName = tarString(content, 0, content.length);
                } else if (type == 'x' || type == 'g') {
                    throw new IOException("Unsupported extended Node header archive metadata");
                } else {
                    Path relative = Path.of(name);
                    if (relative.isAbsolute() || relative.normalize().startsWith("..") || name.contains("\\")) {
                        throw new IOException("Unsafe Node header archive path: " + name);
                    }
                    if (type != 0 && type != '0' && type != '5') {
                        throw new IOException("Unsupported linked Node header archive member: " + name);
                    }
                    if (name.startsWith(prefix) && name.length() > prefix.length()) {
                        Path target = root.resolve(name.substring(prefix.length())).normalize();
                        if (!target.startsWith(root)) throw new IOException("Unsafe Node header output path: " + name);
                        if (type == '5') {
                            if (size != 0) throw new IOException("Node archive directory contains data");
                            Files.createDirectories(target);
                        } else {
                            Files.createDirectories(target.getParent());
                            try (OutputStream output = Files.newOutputStream(target)) {
                                copyExactly(input, output, size);
                            }
                        }
                    } else {
                        input.skipNBytes(size);
                    }
                }
                input.skipNBytes((512 - size % 512) % 512);
            }
        }
    }

    public static void verifyHeaderVersion(Path headers, String version) throws IOException {
        Matcher expected = VERSION.matcher(version);
        if (!expected.matches()) throw new IllegalArgumentException("An exact Node version is required");
        String text = Files.readString(headers.resolve("node_version.h"));
        for (int index = 1; index <= 3; index++) {
            String component = List.of("MAJOR", "MINOR", "PATCH").get(index - 1);
            Matcher actual = Pattern.compile("(?m)^#define\\s+NODE_" + component + "_VERSION\\s+(\\d+)\\s*$").matcher(text);
            if (!actual.find() || !actual.group(1).equals(expected.group(index))) {
                throw new IllegalArgumentException("Node headers do not match runtime " + version + ": " + headers);
            }
        }
        for (String required : List.of("node_api.h", "node.h", "uv.h", "v8.h")) {
            if (!Files.isRegularFile(headers.resolve(required))) {
                throw new IllegalArgumentException("Node development headers are incomplete; missing " + required);
            }
        }
    }

    private static String execute(List<String> command, Path directory, Path temporary) throws IOException, InterruptedException {
        Path log = Files.createTempFile(temporary, "native-command-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(Map.of("TMPDIR", temporary.toString(), "TMP", temporary.toString(), "TEMP", temporary.toString()));
            process = builder.start();
            process.getOutputStream().close();
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
            String output = Files.readString(log);
            if (!finished || process.exitValue() != 0) {
                throw new IOException("Native command " + command.getFirst() + (finished ? " exited " + process.exitValue() : " timed out")
                        + ":\n" + output.substring(Math.max(0, output.length() - 12000)));
            }
            return output;
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            Files.deleteIfExists(log);
        }
    }

    private static Options parse(String[] arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> allowed = Set.of("--project-dir", "--addon-dir", "--build-dir", "--node", "--node-version", "--headers-dir", "--cc", "--cxx", "--linker");
        boolean offline = false;
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument.equals("--offline")) {
                offline = true;
                continue;
            }
            if (!allowed.contains(argument) || index + 1 == arguments.length || values.put(argument, arguments[++index]) != null) {
                throw new IllegalArgumentException("Unknown, repeated or incomplete option: " + argument);
            }
        }
        for (String required : List.of("--project-dir", "--addon-dir", "--build-dir")) {
            if (!values.containsKey(required)) throw new IllegalArgumentException("Missing " + required);
        }
        Path project = Path.of(values.get("--project-dir")).toAbsolutePath().normalize();
        return new Options(project, Path.of(values.get("--addon-dir")).toAbsolutePath().normalize(),
                Path.of(values.get("--build-dir")).toAbsolutePath().normalize(), values.getOrDefault("--node", "node"),
                values.get("--node-version"), values.containsKey("--headers-dir") ? Path.of(values.get("--headers-dir")).toAbsolutePath().normalize() : null,
                values.get("--cc"), values.get("--cxx"), values.get("--linker"), offline);
    }

    private static String exactVersion(String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) throw new IllegalArgumentException("An exact released Node version is required: " + version);
        return matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
    }

    private static List<String> option(Map<String, Object> target, String name) {
        List<String> values = new ArrayList<>(strings(target.getOrDefault(name, List.of()), name));
        values.removeAll(strings(target.getOrDefault(name + "!", List.of()), name + "!"));
        return values;
    }

    private static String environmentCompiler(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Path resolveInput(Path addon, String value) {
        return addon.resolve(literal(value)).normalize();
    }

    private static String literal(String value) {
        if (value.contains("<") || value.contains("\u0000") || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("Unsupported binding expansion or invalid value: " + value);
        }
        return value;
    }

    private static String string(Object value, String description) {
        if (!(value instanceof String text)) throw new IllegalArgumentException(description + " must be a string");
        return text;
    }

    private static List<String> strings(Object value, String description) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(description + " must be a string list");
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(string(item, description));
        return result;
    }

    private static Map<String, Object> object(Object value, String description) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(description + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) result.put(string(entry.getKey(), description + " key"), entry.getValue());
        return result;
    }

    private static void copyLimited(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[65536];
        long written = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            written += count;
            if (written > limit) throw new IOException("Node release download exceeds its size limit");
            output.write(buffer, 0, count);
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[65536];
        while (length > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, length));
            if (count == -1) throw new IOException("Truncated Node header archive member");
            output.write(buffer, 0, count);
            length -= count;
        }
    }

    private static long octal(byte[] bytes, int offset, int length) throws IOException {
        String value = tarString(bytes, offset, length).strip();
        try {
            return value.isEmpty() ? 0 : Long.parseLong(value, 8);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid Node archive numeric field", exception);
        }
    }

    private static String tarString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var entries = Files.walk(directory)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
        }
    }
}
