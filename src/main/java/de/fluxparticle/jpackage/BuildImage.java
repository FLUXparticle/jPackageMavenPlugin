package de.fluxparticle.jpackage;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Scanner;

import static java.lang.String.join;

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

    @Parameter(defaultValue = "${project}", required = true)
    private MavenProject project;

    @Parameter(property = "name", required = true, readonly = true)
    private String name;

    @Parameter(property = "mainClass", required = true, readonly = true)
    private String mainClass;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        Path javaHome = Path.of(System.getProperty("java.home"));

        Path jpackage = javaHome.resolve("bin/jpackage");

        try {
            String modulePath = join(":", project.getRuntimeClasspathElements());

            String target = project.getBuild().getDirectory();

            String[] cmdarray = {
                    jpackage.toString(),
                    "--type", "app-image",
                    "--name", name,
                    "--module-path", modulePath,
                    "--module", mainClass,
                    "--dest", target,
//                    "--verbose",
            };

            Process process = Runtime.getRuntime().exec(cmdarray);

            try (Scanner sc = new Scanner(process.getInputStream())) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();
                    System.out.println(line);
                }
            }
        } catch (IOException | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException(e.toString(), e);
        }
    }

}
