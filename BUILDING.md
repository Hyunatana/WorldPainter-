# Building WorldPainter+

## Dependencies

The desktop application uses ModernDocking 1.4.8 for docking and the open-source JIDE Common Layer 3.7.15 for Swing widgets. Both are downloaded from Maven Central. No JIDE account, commercial jars, evaluation licence, or licence key is required.

WorldPainter's other dependencies continue to use the repositories declared in the POMs, including the existing WorldPainter repository. Maven needs network access for dependencies that are not already cached.

## Java 17 toolchain

Install a Java 17 **JDK**, including desktop/AWT support. Configure `~/.m2/toolchains.xml` to point at it:

```xml
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides><version>17</version></provides>
    <configuration><jdkHome>/path/to/jdk-17</jdkHome></configuration>
  </toolchain>
</toolchains>
```

See the [Maven toolchains guide](https://maven.apache.org/guides/mini/guide-using-toolchains.html) for platform-specific configuration.

## Build and test

From the repository root:

```sh
mvn -B -f WorldPainter/pom.xml install
```

To build and test the desktop application and its dependencies only:

```sh
mvn -B -f WorldPainter/pom.xml -pl WPGUI -am verify
```

Native docking tests need a display. On Linux CI, use Xvfb:

```sh
xvfb-run -a mvn -B -f WorldPainter/pom.xml -pl WPGUI -am -Djava.awt.headless=false verify
```

Without a display, native window tests are skipped. The smaller AI test profile remains available:

```sh
mvn -B -f WorldPainter/pom.xml -Pai-tests -pl WPAIHeadlessTests -am -Djava.awt.headless=true verify
```

## Run

After `install`, run:

```sh
mvn -f WorldPainter/WPGUI/pom.xml exec:exec
```

The main class is `org.pepsoft.worldpainter.Main`. See [AI_ASSISTANT.md](AI_ASSISTANT.md) for the native assistant and JIDE-free command-line world generation/export. See [DOCKING_FRAMEWORK.md](DOCKING_FRAMEWORK.md) for the workspace framework, layouts and migration behavior.
