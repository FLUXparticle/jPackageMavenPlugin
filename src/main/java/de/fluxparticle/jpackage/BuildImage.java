package de.fluxparticle.jpackage;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.modules.ModuleDeclaration;
import com.github.javaparser.ast.modules.ModuleExportsDirective;
import com.github.javaparser.ast.modules.ModuleRequiresDirective;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
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
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResult;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ModuleVisitor;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;

import static com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_9;
import static java.lang.String.join;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.objectweb.asm.Opcodes.ACC_MANDATED;
import static org.objectweb.asm.Opcodes.ACC_MODULE;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;
import static org.objectweb.asm.Opcodes.V9;

/**
 * jPackageMavenPlugin - A Maven Plugin to patch all non-modular dependencies and runs jpackage (JDK 14)
 * Copyright (C) 2020  Sven Reinck
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
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

    @Parameter(defaultValue = "${project}", required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    @Parameter(property = "skip", readonly = true)
    private boolean skip;

    @Parameter(property = "name", readonly = true)
    private String name;

    @Parameter(property = "mainClass", readonly = true)
    private String mainClass;

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

            List<String> classpathElements = processJars(project.getRuntimeClasspathElements(), modulesDir);

            Path appDir = Path.of(target, name + ".app");
            if (Files.exists(appDir)) {
                System.out.println("Deleting: " + appDir);
                deleteDir(appDir);
            }

            String modulePath = join(";", classpathElements);
            if (!jPackage(name, version, modulePath, mainClass, target)) {
                throw new MojoExecutionException("jpackage error");
            }
        } catch (IOException | DependencyResolutionRequiredException | InterruptedException e) {
            throw new MojoExecutionException(e.toString(), e);
        }
    }

    private List<String> processJars(List<String> classpathElements, Path modulesDir) throws IOException, InterruptedException {
        List<String> result = new ArrayList<>();

        List<List<String>> lines = new ArrayList<>();
        lines.add(Arrays.asList("classpathElements:", "modular:"));
        for (int i = 0; i < classpathElements.size(); i++) {
            String classpathElement = classpathElements.get(i);
            Path path = Path.of(classpathElement);
            String fileName = path.getFileName().toString();
            if (!fileName.endsWith(".jar")) {
                result.add(classpathElement);
                continue;
            }

            List<String> line = new ArrayList<>();
            line.add(fileName);

            String modular;
            if (isModular(classpathElement)) {
                result.add(classpathElement);
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
                    String modulePath = classpathElements.stream()
                            .filter(p -> p.endsWith(".jar"))
                            .filter(p -> !p.equals(path.toString()))
                            .collect(joining(";"));
                    newElement = fix(modulesDir, modulePath, path);
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
        }

        printLines(lines);

        return result;
    }

    private static String fix(Path modulesDir, String modulePath, Path jar) throws IOException, InterruptedException {
        String fileName = jar.getFileName().toString();
        Path target = modulesDir.resolve(fileName);

        if (Files.exists(target)) {
            System.out.println("Already Fixed: " + fileName);
        } else try {
            System.out.println("Fix: " + jar);

            if (!jDeps(modulesDir, modulePath, jar)) {
                return null;
            }

            int splitVersion = fileName.lastIndexOf('-');

            String moduleName = fileName.substring(0, splitVersion).replace('-', '.');

            Path mod = modulesDir.resolve(moduleName);
            Path moduleInfo = mod.resolve("module-info.java");

            patch(jar, moduleInfo, target);

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

    private static boolean jDeps(Path modulesDir, String modulePath, Path path) throws IOException, InterruptedException {
        Path jDepsBinary = JAVA_HOME.resolve("bin/jdeps");

//        List<String> versions = getVersions(path.toString());

//        if (versions.isEmpty()) {
        String[] cmdArray = {
                jDepsBinary.toString(),
                "--generate-module-info", modulesDir.toString(),
                "--module-path", modulePath,
                path.toString(),
        };

        return exec(cmdArray);
//        } else for (String version : versions) {
//            String[] cmdArray = {
//                    jDepsBinary.toString(),
//                    "--multi-release", version,
//                    "--generate-module-info", modulesDir.toString(),
//                    "--module-path", modulePath,
//                    path.toString(),
//            };
//
//            exec(cmdArray);
//        }
    }

    private String replaceKotlinStdLib(String fileName) {
        String element;
        ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
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
                sb.append(delimiter); delimiter = "  ";

                String str = line.get(i);
                sb.append(str);
                sb.append(" ".repeat(cols.get(i) - str.length()));
            }
            System.out.println(sb);
        }
    }

    private static boolean jPackage(String name, String version, String modulePath, String mainClass, String target) throws IOException, InterruptedException {
        Path jPackageBinary = JAVA_HOME.resolve("bin/jpackage");

        String[] cmdArray = {
                jPackageBinary.toString(),
                "--type", "app-image",
                "--name", name,
                "--app-version", version,
                "--module-path", modulePath,
                "--module", mainClass,
                "--dest", target,
//                "--verbose",
        };

        System.out.println(String.join(" ", cmdArray));

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

    private static void patch(Path inputJar, Path moduleInfo, Path outputJar) {
        System.out.println("Compile (In-Memory): " + moduleInfo);

        try {
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

            byte[] bytes = classWriter.toByteArray();


            try (JarOutputStream targetStream = new JarOutputStream(new FileOutputStream(outputJar.toString()))) {
                {
                    JarEntry entry = new JarEntry("module-info.class");
                    targetStream.putNextEntry(entry);
                    targetStream.write(bytes);
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

    private static boolean exec(String[] cmdArray) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(cmdArray).inheritIO().start();
                // Runtime.getRuntime().exec(cmdArray);

        try (Scanner sc = new Scanner(process.getInputStream())) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                System.out.println(line);
            }
        }

        if (process.waitFor() != 0) {
            try (Scanner sc = new Scanner(process.getErrorStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    System.out.println(line);
                }
            }
            return false;
        }

        return true;
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

    private static List<String> getVersions(String jar) throws IOException {
        JarFile jarFile = new JarFile(jar);

        Manifest manifest = jarFile.getManifest();
        System.out.println("manifest = " + manifest.getMainAttributes().keySet());

        String prefixVersions = "META-INF/versions/";
        return jarFile.stream()
                .map(ZipEntry::toString)
                .filter(s -> s.startsWith(prefixVersions))
                .map(s -> s.substring(prefixVersions.length()))
                .collect(toList());
    }

}
