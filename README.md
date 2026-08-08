# dm3270 Plugins

This repository contains a collection of plugins for the **dm3270** mainframe terminal emulator. These plugins are designed to automate interactions, manipulate screens, and extract information from the terminal sessions.

## Project Structure

Each plugin resides in its own directory. The currently available plugins are:

* **FanLogon**: Automates the logon process to a TSO/ISPF session (specifically configured for "FanDeZhi" environments).
* **FanLogoff**: Automates the logoff process.
* **ShowDataset**: An example plugin to capture and display mainframe dataset information.
* **ShowFields**: A utility plugin to show and debug screen fields (includes debugging stages and tables).

Each directory contains a `src` folder with the Java source code for the plugin, following the `com.bytezone.plugins` package structure.

## Dependencies

The plugins depend on the core `dm3270` emulator classes. A pre-compiled "fat JAR" containing these dependencies is provided in the root directory:
* `dm3270-1.0.0-SNAPSHOT-all.jar`

Since the plugins also use JavaFX (e.g., for alerts and stages), you need to make sure you are compiling with a JDK that includes JavaFX (like JDK 8) or you have the JavaFX modules available in your environment if you are using newer Java versions (Java 11+).

## Build with Maven (recommended)

The repository is a Maven multi-module project — one module per plugin. It needs the
`dm3270` artifact in your local repository first:

```bash
# once, from the dm3270 checkout
mvn install -DskipTests

# then, from this repository
mvn package        # builds DownloadDataset/target/DownloadDataset.jar and friends
mvn test           # runs the unit tests
```

If you only have the released fat JAR (and not the `dm3270` sources), install it under the
coordinates the poms expect:

```bash
mvn install:install-file -Dfile=dm3270-1.0.0-SNAPSHOT-all.jar \
    -DgroupId=com.bytezone -DartifactId=dm3270 -Dversion=1.0.0-SNAPSHOT -Dpackaging=jar
```

See [TESTING.md](../dm3270/TESTING.md) in the `dm3270` repository for the module map and
the test strategy covering both projects.

## How to Build a Plugin manually

The manual route below still works and needs no Maven. To build a plugin, you must compile
its `.java` files using the `dm3270` JAR file in your classpath, and then package the
resulting `.class` files into a `.jar` archive.

Here is a step-by-step example of how to build the `ShowFields` plugin using standard `javac` and `jar` tools from the command line:

### 1. Compile the source code

Navigate to the root directory of this repository and run `javac`, setting the classpath (`-cp`) to include the `dm3270` jar file. Output the compiled classes to an `out` directory inside the plugin's folder.

```bash
# Create an output directory for the compiled classes
mkdir -p ShowFields/out

# Compile the java files
javac -cp "dm3270-1.0.0-SNAPSHOT-all.jar" -d ShowFields/out ShowFields/src/com/bytezone/plugins/*.java
```

*(Note: If you are using Java 11 or higher, you may need to add JavaFX to the module path during compilation: `--module-path /path/to/javafx/lib --add-modules javafx.controls`)*

### 2. Package the Plugin into a JAR

Once compiled, use the `jar` command to create the plugin JAR file:

```bash
# Package the contents of the 'out' directory into a .jar file
jar cvf ShowFields.jar -C ShowFields/out .
```

This will create `ShowFields.jar` in the current directory.

### 3. Install the Plugin

To use the plugin, place the generated `.jar` file (e.g., `ShowFields.jar`) into the appropriate `plugins` directory of your `dm3270` emulator installation, or load it according to the emulator's instructions.
