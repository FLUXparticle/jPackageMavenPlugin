# jPackageMavenPlugin
This Maven Plugin patches all non-modular dependencies of your project and runs jpackage (JDK 14) to build a platform specific Runtime Image

This project is in a very early state. It does what "I need it to do" but maybe it is also helpful for someone else. If you miss a feature feel free to issue a feature request.

To use it just add this to your `pom.xml`:

    <build>
        <plugins>
            <plugin>
                <groupId>de.fluxparticle</groupId>
                <artifactId>jpackage-maven-plugin</artifactId>
                <version>0.0.1</version>
                <configuration>
                    <name>${image.name}</name>
                    <mainClass>${module.name}/${main.class}</mainClass>
                </configuration>
            </plugin>
        </plugins>
    </build>

You can build a Runtime Image with:

    mvn jpackage:image
    
