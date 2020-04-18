package de.fluxparticle.jpackage;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleExportsDirective;
import com.github.javaparser.ast.modules.ModuleRequiresDirective;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.github.fge.lambdas.Throwing.consumer;
import static com.github.fge.lambdas.Throwing.predicate;
import static com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_9;
import static java.lang.String.join;
import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.eclipse.aether.util.graph.transformer.ConflictResolver.CONFIG_PROP_VERBOSE;
import static org.objectweb.asm.Opcodes.ACC_MANDATED;
import static org.objectweb.asm.Opcodes.ACC_MODULE;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.V9;

/**
 * jPackageMavenPlugin - A Maven Plugin to patch all non-modular dependencies and runs jpackage (JDK 14)
 * Copyright (C) 2020  Sven Reinck
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see https://www.gnu.org/licenses/gpl-3.0
 */
@Mojo(name = "image", requiresDependencyResolution = ResolutionScope.RUNTIME)
@Execute(phase = LifecyclePhase.COMPILE)
public class BuildImage extends AbstractMojo {

    private static final Path JAVA_HOME = Path.of(System.getProperty("java.home"));

    static {
        StaticJavaParser.getConfiguration().setLanguageLevel(JAVA_9);
    }

    @Component
    private ArtifactResolver artifactResolver;

    @Component(hint = "default")
    private ProjectDependenciesResolver projectDependenciesResolver;

    @Parameter(defaultValue = "${project}", required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${reactorProjects}", readonly = true, required = true)
    private List<MavenProject> reactorProjects;

    @Parameter(property = "skip", readonly = true)
    private boolean skip;

    @Parameter(property = "name", readonly = true)
    private String name;

    @Parameter(property = "mainClass", readonly = true)
    private String mainClass;

    @Parameter(property = "verbose", readonly = true)
    private boolean verbose;

    private SortedMap<String, SortedSet<String>> graph = new TreeMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            return;
        }

        if (name == null) {
            throw new MojoFailureException("name required");
        }

        if (mainClass == null) {
            throw new MojoFailureException("mainClass required");
        }

        try {
            String version = project.getArtifact().getVersion().replace("-SNAPSHOT", "");

            String target = project.getBuild().getDirectory();
            Path modulesDir = Path.of(target, "modules");

            ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            buildingRequest.setProject(project);

            DefaultDependencyResolutionRequest resolutionRequest = new DefaultDependencyResolutionRequest(project, getVerboseRepositorySession(session.getRepositorySession()));

            DependencyNode root = projectDependenciesResolver.resolve(resolutionRequest).getDependencyGraph();

            List<List<String>> lines = new ArrayList<>();
            lines.add(asList("classpathElements:", "modular:"));

            Map<String, Set<String>> scopes = new HashMap<>();

            root.accept(new DependencyVisitor() {

                final Deque<String> stack = new LinkedList<>();

                @Override
                public boolean visitEnter(DependencyNode dependencyNode) {
                    Dependency dependency = dependencyNode.getDependency();
                    if (dependency != null) {
                        Artifact artifact = dependencyNode.getArtifact();
                        String parentFile = stack.peekLast();
                        String artifactFile = artifact.getFile().toString();
                        System.out.println("  ".repeat(stack.size()) + artifact + " (" + dependency.getScope() + ")");
                        switch (dependency.getScope()) {
                            case "compile":
                            case "runtime":
                                scopes.computeIfAbsent(artifactFile, key -> new HashSet<>()).add(dependency.getScope());
                                break;
                            case "test":
                                return false;
                        }
                        switch (artifact.getGroupId()) {
                            case "org.openjfx":
                            case "javax.xml.bind":
//                            case "com.sun.activation":
                            case "com.sun.xml.bind":
                                scopes.computeIfAbsent(artifactFile, key -> new HashSet<>()).add("runtime");
                        }
                        stack.addLast(artifactFile);
                        graph.computeIfAbsent(artifactFile, key -> new TreeSet<>());
                        if (parentFile != null) {
                            graph.get(parentFile).add(artifactFile);
                        }
                    }
                    return true;
                }

                @Override
                public boolean visitLeave(DependencyNode dependencyNode) {
                    Dependency dependency = dependencyNode.getDependency();
                    if (dependency != null) {
                        switch (dependency.getScope()) {
//                            case "runtime":
                            case "test":
                                return true;
                        }
                        stack.removeLast();
                    }
                    return true;
                }
            });

            Stream<String> runtimeA = scopes.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(singleton("runtime")))
                    .map(Entry::getKey);

            Stream<String> runtimeB = graph.keySet().stream()
                    .filter(artifactFile -> artifactFile.endsWith(".jar"))
                    .filter(predicate(BuildImage::isModular));

            List<String> runtime = Stream.concat(runtimeA, runtimeB)
                    .collect(toList());

            System.out.println("Runtime:");
            runtime.forEach(System.out::println);

            List<String> result = new ArrayList<>();

            for (String artifactFile : graph.keySet()) {
                try {
                    if (artifactFile.endsWith(".jar")) {
                        String fileName = artifactFile.substring(artifactFile.lastIndexOf('/') + 1);

                        List<String> line = new ArrayList<>();
                        line.add(fileName);

                        String modular;
                        if (isModular(artifactFile)) {
                            result.add(artifactFile);
                            modular = "yes";
                        } else {
                            String newElement = null;
                            String action = null;

                            if (fileName.startsWith("kotlin-stdlib-")) {
                                newElement = replaceKotlinStdLib(fileName);
                                action = "replaced";
                            }

                            if (fileName.startsWith("javafx-")) {
                                newElement = "";
                            }

                            if (newElement == null) {
                                List<String> modulePath = ((fileName.startsWith("javax.activation-api")) ? dependencies(artifactFile) :
                                        /*(runtime.contains(artifactFile))
                                        ? dependencies(artifactFile)
                                        : */Stream.concat(runtime.stream(), dependencies(artifactFile))
                                ).collect(toList());

                                newElement = fix(modulesDir, modulePath, Path.of(artifactFile));
                                action = "fixed";
                            }

                            if (newElement == null) {
                                modular = "no";
                            } else if (newElement.isEmpty()) {
                                modular = "removed";
                            } else {
                                result.add(newElement);
                                modular = action;
                            }
                        }
                        line.add(modular);
                        lines.add(line);
                    } else {
                        result.add(artifactFile);
                    }
                } catch (RuntimeException | IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            result.add(project.getBuild().getOutputDirectory());

            printLines(lines);

            Path appDir = Path.of(target, name + ".app");
            if (Files.exists(appDir)) {
                System.out.println("Deleting: " + appDir);
                deleteDir(appDir);
            }

            System.out.println("Result:");
            result.forEach(System.out::println);

            String modulePath = join(":", result);
            if (!jPackage(name, version, modulePath, mainClass, target)) {
                throw new MojoExecutionException("jpackage error");
            }
        } catch (IOException | InterruptedException | DependencyResolutionException e) {
            throw new MojoExecutionException(e.toString(), e);
        }
    }

    private Stream<String> dependencies(String artifactID) {
        return graph.get(artifactID).stream()
                .flatMap(childID -> Stream.concat(Stream.of(childID), dependencies(childID)));
    }

    private static String fix(Path modulesDir, List<String> modulePath, Path jar) throws IOException, InterruptedException {
        String fileName = jar.getFileName().toString();
        Path target = modulesDir.resolve(fileName);

        if (Files.exists(target)) {
            System.out.println("Already Fixed: " + fileName);
        } else try {
            System.out.println("Fix: " + jar);

            SortedSet<Integer> versions = new TreeSet<>();
            if (modulePath.stream().anyMatch(predicate(file -> new JarFile(file).isMultiRelease()))) {
                modulePath.forEach(consumer((String file) -> versions.addAll(getVersions(file))));
            }

            if (!jDeps(modulesDir, versions, modulePath, jar)) {
                return null;
            }

            String moduleName = ModuleFinder.of(jar)
                    .findAll().stream().findFirst()
                    .map(ModuleReference::descriptor)
                    .map(ModuleDescriptor::name)
                    .get();

            Path mod = modulesDir.resolve(moduleName);

            patch(jar, mod, versions, target);

/*
                Path out = mod.resolve("classes");

                extract(jar, out);

                if (!javaCompiler(modulePath, moduleInfo, out)) {
                    return null;
                }

                pack(out, target);
*/
        } finally {
            System.out.println();
        }

        return target.toString();
    }

    private static void extract(Path jar, Path out) throws IOException {
        deleteDir(out);
        System.out.println("Extract: " + jar);
        JarFile jarFile = new JarFile(jar.toString());
        Iterator<JarEntry> iterator = jarFile.entries().asIterator();
        while (iterator.hasNext()) {
            JarEntry entry = iterator.next();
            String name = entry.getName();
            Path path = out.resolve(name);
            if (name.endsWith("/")) {
                Files.createDirectories(path);
            } else {
                Files.copy(jarFile.getInputStream(entry), path);
            }
        }
    }

    private static void pack(Path out, Path jar) throws IOException {
        System.out.println("Pack: " + jar);
        try (JarOutputStream target = new JarOutputStream(new FileOutputStream(jar.toString()))) {
            Files.walkFileTree(out, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    JarEntry entry = new JarEntry(out.relativize(dir).toString() + "/");
                    target.putNextEntry(entry);
                    target.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    JarEntry entry = new JarEntry(out.relativize(file).toString());
                    target.putNextEntry(entry);
                    Files.copy(file, target);
                    target.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static boolean jDeps(Path modulesDir, SortedSet<Integer> versions, List<String> modulePath, Path path) throws IOException, InterruptedException {
        Path jDepsBinary = JAVA_HOME.resolve("bin/jdeps");

        if (versions.isEmpty()) {
            List<String> cmdArray = new ArrayList<>();

            cmdArray.add(jDepsBinary.toString());
            cmdArray.addAll(asList("--generate-module-info", modulesDir.toString()));
//            cmdArray.add("--ignore-missing-deps");

            if (!modulePath.isEmpty()) {
                cmdArray.addAll(asList("--module-path", String.join(":", modulePath)));
            }

            cmdArray.add(path.toString());

            return exec(cmdArray);
        } else return versions.stream().allMatch(predicate(version -> {
            System.out.println("Version: " + version);
            List<String> cmdArray = new ArrayList<>();

            cmdArray.add(jDepsBinary.toString());
            cmdArray.addAll(asList("--generate-module-info", modulesDir.toString()));
            cmdArray.add("--ignore-missing-deps");
            cmdArray.addAll(asList("--multi-release", version.toString()));

            if (!modulePath.isEmpty()) {
                cmdArray.addAll(asList("--module-path", String.join(":", modulePath)));
            }

            cmdArray.add(path.toString());

            return exec(cmdArray);
        }));
    }

    private String replaceKotlinStdLib(String fileName) {
        String element;
        ProjectBuildingRequest buildingRequest = session.getProjectBuildingRequest();
        DefaultArtifactCoordinate artifactCoordinate = toArtifactCoordinate(fileName);
        artifactCoordinate.setClassifier("modular");
        try {
            ArtifactResult artifactResult = artifactResolver.resolveArtifact(buildingRequest, artifactCoordinate);
            element = artifactResult.getArtifact().getFile().toString();
        } catch (ArtifactResolverException | IllegalArgumentException e) {
            element = null;
        }
        return element;
    }

    private static void deleteDir(Path dir) throws IOException {
        Files.walkFileTree(dir, new FileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void printLines(List<List<String>> lines) {
        Map<Integer, Integer> cols = lines.stream()
                .flatMap(line -> IntStream.range(0, line.size()).boxed()
                        .collect(toMap(
                                identity(),
                                idx -> line.get(idx).length()
                        ))
                        .entrySet().stream()
                )
                .collect(toMap(
                        Entry::getKey,
                        Entry::getValue,
                        Math::max
                ));

        for (List<String> line : lines) {
            StringBuilder sb = new StringBuilder();
            String delimiter = "";
            for (int i = 0; i < line.size(); i++) {
                sb.append(delimiter);
                delimiter = "  ";

                String str = line.get(i);
                sb.append(str);
                sb.append(" ".repeat(cols.get(i) - str.length()));
            }
            System.out.println(sb);
        }
    }

    private boolean jPackage(String name, String version, String modulePath, String mainClass, String target) throws IOException, InterruptedException {
        Path jPackageBinary = JAVA_HOME.resolve("bin/jpackage");

        List<String> cmdArray = new ArrayList<>();

        cmdArray.add(jPackageBinary.toString());
        cmdArray.addAll(asList("--type", "app-image"));
        cmdArray.addAll(asList("--name", name));
        cmdArray.addAll(asList("--app-version", version));
        cmdArray.addAll(asList("--module-path", modulePath));
        cmdArray.addAll(asList("--module", mainClass));
        cmdArray.addAll(asList("--dest", target));

        if (verbose) {
            cmdArray.add("--verbose");
        }

        System.out.println(join(" ", cmdArray));

        return exec(cmdArray);
    }

    private static boolean javaCompiler(String modulePath, Path moduleInfo, Path out) throws IOException, InterruptedException {
        System.out.println("Compile: " + moduleInfo);

        Path javaCompilerBinary = JAVA_HOME.resolve("bin/javac");

        String[] cmdArray = {
                javaCompilerBinary.toString(),
                "--module-path", modulePath,
                "-d", out.toString(),
                moduleInfo.toString(),
        };

        return exec(cmdArray);
    }

    private static void patch(Path inputJar, Path mod, SortedSet<Integer> versions, Path outputJar) {

        try {

            try (JarOutputStream targetStream = new JarOutputStream(new FileOutputStream(outputJar.toString()))) {
                if (versions.isEmpty()) {
                    Path moduleInfo = mod.resolve("module-info.java");
                    JarEntry entry = new JarEntry("module-info.class");
                    targetStream.putNextEntry(entry);
                    targetStream.write(compile(moduleInfo));
                    targetStream.closeEntry();
                } else for (Integer version : versions) {
                    Path path = Path.of("versions", version.toString(), "module-info.java");
                    Path moduleInfo = mod.resolve(path);
                    JarEntry entry = new JarEntry(Path.of("META-INF").resolve(path.resolveSibling("module-info.class")).toString());
                    targetStream.putNextEntry(entry);
                    targetStream.write(compile(moduleInfo));
                    targetStream.closeEntry();
                }

                JarFile jarFile = new JarFile(inputJar.toString());
                Iterator<JarEntry> iterator = jarFile.entries().asIterator();
                while (iterator.hasNext()) {
                    JarEntry entry = iterator.next();
                    String name = entry.getName();

                    if (name.endsWith("/")) {
                        targetStream.putNextEntry(entry);
                        targetStream.closeEntry();
                    } else if (name.equals("META-INF/MANIFEST.MF")) {
                        JarEntry newEntry = new JarEntry(name);
                        targetStream.putNextEntry(newEntry);

                        try (InputStream stream = jarFile.getInputStream(entry)) {
                            Manifest manifest = new Manifest(stream);

                            Attributes mainAttributes = manifest.getMainAttributes();
                            mainAttributes.remove("Automatic-Module-Name");
                            if (!versions.isEmpty()) {
                                mainAttributes.putValue("Multi-Release", "true");
                            }

                            System.out.println("Manifest:");
                            mainAttributes.entrySet().forEach(System.out::println);

                            manifest.write(targetStream);
                        }

                        targetStream.closeEntry();
                    } else {
                        targetStream.putNextEntry(entry);

                        try (InputStream stream = jarFile.getInputStream(entry)) {
                            stream.transferTo(targetStream);
                        }

                        targetStream.closeEntry();
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] compile(Path moduleInfo) throws IOException {
        System.out.println("Compile (In-Memory): " + moduleInfo);
        CompilationUnit compilationUnit = StaticJavaParser.parse(moduleInfo);

        ModuleDeclaration module = compilationUnit.getModule().get();

        ClassWriter classWriter = new ClassWriter(0);
        classWriter.visit(V9, ACC_MODULE, "module-info", null, null, null);

        ModuleVisitor mv = classWriter.visitModule(module.getNameAsString(), ACC_SYNTHETIC, null);

        for (ModuleRequiresDirective requires : module.findAll(ModuleRequiresDirective.class)) {
            mv.visitRequire(
                    requires.getName().asString(),
                    0,
                    null
            );
        }

        for (ModuleExportsDirective export : module.findAll(ModuleExportsDirective.class)) {
            mv.visitExport(
                    export.getNameAsString().replace('.', '/'),
                    0,
                    export.getModuleNames()
                            .stream()
                            .map(Name::toString)
                            .toArray(String[]::new)
            );
        }

        mv.visitRequire("java.base", ACC_MANDATED, null);
        mv.visitEnd();

        classWriter.visitEnd();

        return classWriter.toByteArray();
    }

    private static boolean exec(List<String> cmdArray) throws IOException, InterruptedException {
        return exec(cmdArray.toArray(String[]::new));
    }

    private static boolean exec(String[] cmdArray) throws IOException, InterruptedException {
        return new ProcessBuilder(cmdArray).inheritIO().start().waitFor() == 0;
    }

    private DefaultArtifactCoordinate toArtifactCoordinate(String fileName) {
        DefaultArtifactCoordinate artifactCoordinate = new DefaultArtifactCoordinate();

        int splitVersion = fileName.lastIndexOf('-');
        int splitExtension = fileName.lastIndexOf('.');

        artifactCoordinate.setGroupId("org.jetbrains.kotlin");
        artifactCoordinate.setArtifactId(fileName.substring(0, splitVersion));
        artifactCoordinate.setVersion(fileName.substring(splitVersion + 1, splitExtension));
        artifactCoordinate.setExtension(fileName.substring(splitExtension + 1));

        return artifactCoordinate;
    }

    private static boolean isModular(String jar) throws IOException {
        return new JarFile(jar).stream()
                .anyMatch(jarEntry -> jarEntry.getName().equals("module-info.class"));
    }

    private static List<Integer> getVersions(String jar) throws IOException {
        JarFile jarFile = new JarFile(jar);

        Path prefixVersions = Path.of("META-INF/versions");
        return jarFile.stream()
                .map(entry -> Path.of(entry.toString()))
                .filter(p -> p.startsWith(prefixVersions) && p.getNameCount() == 3)
                .map(p -> Integer.parseInt(p.getName(2).toString()))
                .collect(toList());
    }

    private static RepositorySystemSession getVerboseRepositorySession(RepositorySystemSession repositorySession) {
        DefaultRepositorySystemSession verboseRepositorySession = new DefaultRepositorySystemSession(repositorySession);
        verboseRepositorySession.setConfigProperty(CONFIG_PROP_VERBOSE, "true");
//        verboseRepositorySession.setReadOnly();
        return verboseRepositorySession;
    }

}
