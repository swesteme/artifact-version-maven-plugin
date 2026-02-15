package de.westemeyer.plugins.maven.versions;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The generate-service goal has exactly two responsibilities. First: generate a simple service class implementing
 * the ArtifactVersionService interface to provide artifact coordinates and version for ArtifactVersionCollector.
 * Second: generate a new service loader definition file so the service loader can pick up the information.
 */
@Mojo(name = "generate-service", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
@SuppressWarnings("checkstyle:VisibilityModifier")
public class GenerateServiceMojo extends AbstractMojo {
    /**
     * Name (or prefix) of the generated parent artifact variable.
     */
    private static final String PARENT_VARIABLE_NAME = "parentArtifact";

    /**
     * Constant string for use in generated class.
     */
    private static final String NULL_STRING = "null";

    /**
     * The project object is injected with information from a project's pom.xml.
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    MavenProject project;

    /**
     * The target source folder for a generated service class. Should in most cases be left alone, just make sure to
     * point your IDE to its location (which it should probably do automatically).
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/artifact-versions", required = true)
    File targetFolder;

    /**
     * The package name to use for generated service class. Default is group ID plus ".versions" postfix.
     */
    @Parameter
    String packageName;

    /**
     * The service class name to use for generated class (without package). Default is camel case artifact ID plus "VersionService" postfix.
     */
    @Parameter
    String serviceClass;

    /**
     * The autoconfiguration class name to use for generated class (without package). Default is camel case artifact ID plus "AutoConfiguration" postfix.
     */
    @Parameter
    String autoConfigurationClass;

    /**
     * The kind of service to generate.
     */
    @Parameter
    ServiceType serviceType = ServiceType.SPRING_BOOT;

    /**
     * In some cases we may not need an automatically generated AutoConfiguration class. For example in cases where one already exists,
     * or where base packages are used instead.
     */
    @Parameter
    boolean skipSpringBootAutoConfiguration = false;

    @Override
    public void execute() throws MojoFailureException {
        String packaging = project.getPackaging();
        // parent poms in multi-module projects do not need a service class
        if (packaging == null || !packaging.equalsIgnoreCase("pom")) {
            generateFiles();
        }
    }

    void generateFiles() throws MojoFailureException {
        // optional package name parameter can be "guessed" from group ID
        packageName = setUpParameterValue("Package name", packageName, () -> project.getGroupId() + ".versions");

        // optional service class name parameter can be "guessed" from artifact ID
        serviceClass = setUpParameterValue("Service class", serviceClass, this::determineServiceClassName);

        // create autoconfig class name
        autoConfigurationClass = setUpParameterValue("Autoconfiguration class",
                autoConfigurationClass, () -> serviceClass.replaceAll("(VersionService)$", "AutoConfiguration"));

        // template values to be replaced in template resource files to create meaningful classes
        Map<String, String> templateValues = getTemplateValues(autoConfigurationClass);

        // write the service class
        writeClassFile(getTemplateResourceFileName(), templateValues, serviceClass);

        // no need for a Spring service manifest
        if (serviceType.equals(ServiceType.NATIVE)) {
            // write the service manifest
            writeServiceManifest("services", "de.westemeyer.version.core.api.ArtifactVersionService",
                    packageName + "." + serviceClass);
        } else {
            if (!skipSpringBootAutoConfiguration) {
                // write autoconfiguration class
                writeClassFile("service-template-spring-boot-configuration.txt", templateValues,
                        autoConfigurationClass);

                // write the autoconfiguration manifest
                writeServiceManifest("spring", "org.springframework.boot.autoconfigure.AutoConfiguration.imports",
                        packageName + "." + autoConfigurationClass);
            }
        }

        // add source root for generated source file
        project.addCompileSourceRoot(targetFolder.getPath());
    }

    /**
     * Determine parameter value for an optional parameter.
     *
     * @param description   description of the parameter content (for logging)
     * @param currentValue  initial value (is preserved when set)
     * @param valueSupplier method or lambda to determine a default value
     * @return the final parameter value
     */
    String setUpParameterValue(String description, String currentValue, Supplier<String> valueSupplier) {
        // optional parameter may be empty
        if (currentValue == null || currentValue.isEmpty()) {
            // use value supplier to compute new value
            String suppliedValue = valueSupplier.get();

            // inform user about the choice of package name
            getLog().info(description + " is not defined, using: " + suppliedValue);

            // return computed value
            return suppliedValue;
        }

        return currentValue;
    }

    /**
     * Write the service manifest that binds the generated source file to the ArtifactVersionService.
     *
     * @param subDirectory subdirectory from META-INF
     * @param fileName     the service file name
     * @param fileContent  content for the service file
     * @throws MojoFailureException in case the output file can not be written to META-INF directory
     */
    void writeServiceManifest(String subDirectory, String fileName, String fileContent) throws MojoFailureException {
        // compile META-INF directory name
        File directory = new File(project.getBuild().getOutputDirectory(), "META-INF");

        // append "services" directory
        directory = new File(directory, subDirectory);

        // create target directory (if it does not exist)
        makeDirectories(directory, "META-INF/" + subDirectory);

        // file name is always the same
        File serviceFile = new File(directory, fileName);

        // create output file writer
        try (OutputStream outputStream = createServiceFileOutputStream(
                serviceFile); PrintWriter writer = createPrintWriter(outputStream)) {
            // simply print the generated class name into the generated file
            writer.println(fileContent);
        } catch (IllegalArgumentException | UnsupportedOperationException | SecurityException | IOException e) {
            throw new MojoFailureException("Unable to create new service loader definition file: " + serviceFile);
        }
    }

    PrintWriter createPrintWriter(OutputStream outputStream) {
        return new PrintWriter(outputStream);
    }

    /**
     * Try to create a camel case service class name from the artifact ID. Only necessary if service class name is not
     * configured in plugin execution.
     *
     * @return a brand new (and hopefully meaningful) service class name
     */
    String determineServiceClassName() {
        // get artifact ID from project
        String artifactId = project.getArtifactId();

        // output buffer for generated class name
        StringBuilder output = new StringBuilder();

        // start off with a capital letter
        boolean capitalizeNext = true;

        // iterate all characters in artifact ID
        for (int i = 0; i < artifactId.length(); i++) {
            // get character at current index position
            char thisChar = artifactId.charAt(i);

            // skip dashes and dots...
            if (thisChar == '-' || thisChar == '.') {
                // ... but remember to capitalize next character
                capitalizeNext = true;
            } else if (capitalizeNext) {
                // otherwise append uppercase value of character...
                output.append(String.valueOf(thisChar).toUpperCase());
                capitalizeNext = false;
            } else {
                // or leave character "as it is"
                output.append(thisChar);
            }
        }

        // postfix and return result
        return output + "VersionService";
    }

    /**
     * Write the service java class file.
     *
     * @param templateResourceFileName name of the template file
     * @param templateValues           values to fill into template
     * @param className                the class name to use
     * @throws MojoFailureException in case an IOException occurred
     */
    void writeClassFile(String templateResourceFileName, Map<String, String> templateValues,
                        String className) throws MojoFailureException {
        // need to create path from package components, therefore we have to split the package string...
        String[] packageComponents = packageName.split("\\.");

        // ... starting with the target folder
        File packageDir = targetFolder;

        // ... iterate and append path components
        for (String component : packageComponents) {
            packageDir = new File(packageDir, component);
        }

        // and finally try to create resulting directory path
        makeDirectories(packageDir, "service class package");

        // concatenate class file name
        String fileName = className + ".java";

        // open template file input stream and java source file output stream for generated service file
        try (InputStream inStream = createServiceTemplateResourceStream(templateResourceFileName);
             OutputStream outputStream = createClassFileOutputStream(packageDir, fileName)) {
            // input stream should always be available from resource file
            if (inStream == null) {
                throw new MojoFailureException("Failed to read service template from plugin resources");
            }

            // create reader and writer objects for template and output files respectively
            try (Reader reader = new InputStreamReader(inStream,
                    StandardCharsets.UTF_8); PrintWriter writer = createPrintWriter(outputStream)) {

                // read template file to string builder
                String out = readTemplateFile(reader);

                // iterate map of template values for replacement
                for (Map.Entry<String, String> entry : templateValues.entrySet()) {
                    String value = entry.getValue();
                    if (value == null) {
                        out = out.replace("\"${" + entry.getKey() + "}\"", NULL_STRING);
                    } else {
                        // replace values in string
                        out = out.replace("${" + entry.getKey() + "}", value);
                    }
                }

                // write resulting java source code to output file
                writer.print(out);
            }
        } catch (IOException e) {
            throw new MojoFailureException("Unable to read template file from plugin resources", e);
        }
    }

    /**
     * Set up a map of template variable replacement values.
     *
     * @param autoconfigurationClass the autoconfiguration class name
     * @return map of template variable replacement values
     */
    Map<String, String> getTemplateValues(String autoconfigurationClass) {
        Map<String, String> valueMap = new HashMap<>();
        valueMap.put("package", packageName);
        valueMap.put("serviceClass", serviceClass);
        valueMap.put("configClass", autoconfigurationClass);
        valueMap.put("groupId", project.getGroupId());
        valueMap.put("artifactId", project.getArtifactId());
        valueMap.put("version", project.getVersion());
        valueMap.put("name", replaceLineFeeds(project.getName()));
        valueMap.put("url", project.getUrl());
        valueMap.put("description", replaceLineFeeds(project.getDescription()));
        valueMap.put("timestamp", "" + new Date().getTime());
        valueMap.put("parentArtifactDefinition", getParentArtifactDefinition());
        return valueMap;
    }

    /**
     * Determine the content for the creation of parent artifacts.
     *
     * @return list of parent artifact instantiations as string
     */
    String getParentArtifactDefinition() {
        MavenProject parent = project.getParent();
        if (parent != null) {
            int i = 1;
            String parentVariableName = PARENT_VARIABLE_NAME;
            StringBuilder parentArtifactDefinition = new StringBuilder();
            do {
                StringBuilder buffer = new StringBuilder();
                buffer.append("        BasicArtifact ").append(parentVariableName);
                buffer.append(" = new BasicArtifact(");
                appendStringAndComma(buffer, parent.getGroupId());
                appendStringAndComma(buffer, parent.getArtifactId());
                appendStringAndComma(buffer, parent.getVersion());
                parent = parent.getParent();
                parentVariableName = PARENT_VARIABLE_NAME + i++;
                if (parent == null) {
                    buffer.append(NULL_STRING);
                } else {
                    buffer.append(parentVariableName);
                }
                buffer.append(");\n");
                parentArtifactDefinition.insert(0, buffer);
            } while (parent != null);
            return parentArtifactDefinition.toString();
        }
        return "";
    }

    /**
     * Convenience method used to append new content to a comma separated list.
     *
     * @param buffer  buffer to append to
     * @param content content to append
     */
    private static void appendStringAndComma(StringBuilder buffer, String content) {
        buffer.append("\"").append(content).append("\", ");
    }

    /**
     * Strings in pom.xml files may contain line breaks. Replace these by backslash n to have valid String constants in
     * generated service class files.
     *
     * @param input the input string from pom.xml
     * @return correct string for service class
     */
    static String replaceLineFeeds(String input) {
        return input == null ? null : input.replaceAll("\\r?\\n", "\\\\n").replace("\t", " ");
    }

    /**
     * Read service java class template file from resources.
     *
     * @param reader the file reader instance
     * @return a string with the template file content
     * @throws IOException in case reading the file failed
     */
    String readTemplateFile(Reader reader) throws IOException {
        // the buffer size to use
        int bufferSize = 1024;

        // create an empty buffer
        char[] buffer = new char[bufferSize];

        // create new string builder instance
        StringBuilder out = new StringBuilder();

        // read file content in buffer sized chunks
        for (int numRead; (numRead = reader.read(buffer, 0, buffer.length)) > 0; ) {
            // and append the buffer content to string builder
            out.append(buffer, 0, numRead);
        }

        // return the template file content
        return out.toString();
    }

    /**
     * Check whether directory already exists, otherwise try to create it.
     *
     * @param directory   directory to be created
     * @param description description of directory purpose
     */
    void makeDirectories(File directory, String description) throws MojoFailureException {
        if (!(directory.isDirectory() || directory.mkdirs())) {
            throw new MojoFailureException("Unable to create " + description + " directory: " + directory.getPath());
        }
    }

    /**
     * Create output stream object from service file name. Extracted to achieve code coverage for catch block in
     * {@link #writeServiceManifest(String, String, String)} method.
     *
     * @param serviceFile the service file name
     * @return new output stream object
     * @throws FileNotFoundException in case the file can not be created
     */
    protected OutputStream createServiceFileOutputStream(File serviceFile) throws IOException {
        try {
            return Files.newOutputStream(serviceFile.toPath());
        } catch (InvalidPathException exc) {
            throw new IOException("Invalid path: " + serviceFile, exc);
        }
    }

    /**
     * Create service class output stream.
     *
     * @param packageDir the package directory
     * @param fileName   file name to use
     * @return a new output stream object
     * @throws FileNotFoundException in case the file can not be created
     */
    protected OutputStream createClassFileOutputStream(File packageDir, String fileName) throws IOException {
        // create file object
        File file = new File(packageDir, fileName);

        // check, whether file exists under a name that is similar, but not equal to file name (case-insensitive)
        cleanupExistingFile(file, fileName);

        return Files.newOutputStream(file.toPath());
    }

    /**
     * Delete file that exists under a name that is similar, but not equal to file name (case-insensitive).
     *
     * @param file     file including path
     * @param fileName file name to check
     */
    protected void cleanupExistingFile(File file, String fileName) {
        if (file.exists() && !checkFileExistsCaseSensitive(file, fileName)) {
            try {
                Files.delete(file.toPath());
            } catch (InvalidPathException | IOException e) {
                getLog().warn(
                        "Unable to remove file with different name before generating new artifact version service file. Try cleaning project first.");
            }
        }
    }

    /**
     * Check if a file exists with another combination of uppercase and lowercase characters. May be important when
     * generating a new service class on a Windows or macOS system.
     *
     * @param file     the file to check
     * @param fileName the expected file name to compare to
     * @return whether a file with different spelling exists
     */
    protected boolean checkFileExistsCaseSensitive(File file, String fileName) {
        try {
            return file.getCanonicalFile().getName().equals(fileName);
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Determine the resource file to use as a template.
     *
     * @return the resource file name to use
     */
    protected String getTemplateResourceFileName() {
        String infix = serviceType.equals(ServiceType.NATIVE) ? "" : "-spring-boot";
        final String fileNamePrefix = "service-template";
        if (project.getParent() != null) {
            return fileNamePrefix + infix + "-with-parent.txt";
        }
        return fileNamePrefix + infix + ".txt";
    }

    /**
     * Get service class template resource as input stream.
     *
     * @param templateResourceFileName name of the template file
     * @return the input stream
     */
    protected InputStream createServiceTemplateResourceStream(String templateResourceFileName) {
        return getClass().getResourceAsStream(templateResourceFileName);
    }
}
