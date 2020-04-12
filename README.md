# jPackageMavenPlugin
This Maven Plugin patches all non-modular dependencies of your project and runs jpackage (JDK 14) to build a platform specific Runtime Image

This project is in a very early state. It does what "I need it to do" (building an App Bundle on macOS) but maybe it is also helpful for someone else. If you miss a feature feel free to issue a feature request.

`jdeps` is used to analyse all your dependencies and fix all non-modular artifacts so `jlink` can build a minimal Java Runtime.

Since `jpackage` is only available since JDK 14 you need to run Maven with JDK 14 (or above). You don't need to compile your project with JDK 14.

To use it just add this to your `pom.xml`:

    <build>
        <plugins>
            <plugin>
                <groupId>de.fluxparticle</groupId>
                <artifactId>jpackage-maven-plugin</artifactId>
                <version>0.0.2</version>
                <configuration>
                    <name>${image.name}</name>
                    <mainClass>${module.name}/${main.class}</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>

You can build a Runtime Image with:

    mvn jpackage:image
    
