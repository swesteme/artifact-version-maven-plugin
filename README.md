# Artifact version service maven generator plugin

![Build Status Linux](https://github.com/swesteme/artifact-version-maven-plugin/workflows/Java%20CI%20with%20Maven/badge.svg?branch=main)

The artifact-version-maven-plugin is used to automatically generate artifact version information to be collected by ArtifactVersionCollector somewhere in the classpath.

A more elegant way to make your Java software project aware of its module (jar) dependencies and their versions. No more reading jar manifests, just a simple service loader enabled use of:

```java
// iterate list of artifact dependencies
for (Artifact artifact : ArtifactVersionCollector.collectArtifacts()) {
    // print simple artifact string example
    System.out.println("artifact = " + artifact);
}
```

*NOTE*: all participating modules need this generator plugin in their `pom.xml`, so it is probably a sensible idea to create a master/parent `pom.xml` for all module projects. 

artifact-version-maven-plugin is published under the
[MIT license](http://opensource.org/licenses/MIT). It requires at least Java 8.

## Installation

artifact-version-maven-plugin is available from
[Maven Central](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22de.westemeyer%22%20AND%20a%3A%22artifact-version-maven-plugin%22).

It is used in combination with the [artifact-version-service](https://github.com/swesteme/artifact-version-service) runtime dependency.
```xml
<build>
  <plugins>
    <plugin>
      <groupId>de.westemeyer</groupId>
      <artifactId>artifact-version-maven-plugin</artifactId>
      <version>1.0.0</version>
      <executions>
        <execution>
          <goals>
            <goal>generate-service</goal>
          </goals>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>

<dependencies>
  <dependency>
    <groupId>de.westemeyer</groupId>
    <artifactId>artifact-version-service</artifactId>
    <version>1.0.0</version>
  </dependency>
</dependencies>
```

It is also possible to configure the generator to use target directories and a more specific service class definition:
```xml
<build>
  <plugins>
    <plugin>
      <groupId>de.westemeyer</groupId>
      <artifactId>artifact-version-maven-plugin</artifactId>
      <version>1.0.0-SNAPSHOT</version>
      <executions>
        <execution>
          <goals>
            <goal>generate-service</goal>
          </goals>
        </execution>
      </executions>
      <configuration>
        <packageName>my.package.for.generated.service.class</packageName>
        <serviceClass>MyGeneratedServiceClass</serviceClass>
        <targetFolder>target/generated-sources</targetFolder>
      </configuration>
    </plugin>
  </plugins>
</build>
```

## Usage (of artifact-version-service)

Use `artifact-version-service`s functionality like in the following example:

```java
import de.westemeyer.version.model.Artifact;
import de.westemeyer.version.service.ArtifactVersionCollector;

public class Main {
  public static void main(String[] args) {
    System.out.println("List of artifacts:");
    for (Artifact artifact : ArtifactVersionCollector.collectArtifacts()) {
      System.out.println("artifact = " + artifact);
    }
  }
}
```

to your tests.

## Display generated Code in your IDE
IntelliJ IDEA should show generated Java source files as soon as "Packages" perspective is selected in "Project" view. 

## Contributing

You have three options if you have a feature request, found a bug or
simply have a question about artifact-version-maven-plugin:

* [Write an issue.](https://github.com/swesteme/artifact-version-maven-plugin/issues/new)
* Create a pull request. (See [Understanding the GitHub Flow](https://guides.github.com/introduction/flow/index.html))
* [Write a mail to sebastian@westemeyer.de](mailto:sebastian@westemeyer.de)

## Development Guide

artifact-version-maven-plugin is built with [Maven](http://maven.apache.org/) and must be
compiled using JDK 8. If you want to contribute code then

* Please write a test for your change.
* Ensure that you didn't break the build by running `mvn clean verify -Dgpg.skip`.
* Fork the repo and create a pull request. (See [Understanding the GitHub Flow](https://guides.github.com/introduction/flow/index.html))
