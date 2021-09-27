package de.westemeyer.plugins.maven.versions;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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

    @Override
    public void execute() throws MojoFailureException {
        // optional package name parameter can be "guessed" from group ID
        packageName = setUpParameterValue("Package name", packageName, () -> project.getGroupId() + ".versions");

        // optional service class name parameter can be "guessed" from artifact ID
        serviceClass = setUpParameterValue("Service class", serviceClass, this::determineServiceClassName);

        // write the service class
        writeServiceClass();

        // write the service manifest
        writeServiceManifest();

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
     * @throws MojoFailureException in case the output file can not be written to META-INF directory
     */
    void writeServiceManifest() throws MojoFailureException {
        // compile META-INF directory name
        File directory = new File(project.getBuild().getOutputDirectory(), "META-INF");

        // append "services" directory
        directory = new File(directory, "services");

        // create target directory (if it does not exist)
        makeDirectories(directory, "META-INF/services");

        // file name is always the same
        File serviceFile = new File(directory, "de.westemeyer.version.service.ArtifactVersionService");

        // create output file writer
        try (OutputStream outputStream = createServiceFileOutputStream(serviceFile); PrintWriter writer = new PrintWriter(outputStream)) {
            // simply print the generated class name into the generated file
            writer.println(packageName + "." + serviceClass);
        } catch (IOException e) {
            throw new MojoFailureException("Unable to create new service loader definition file: " + serviceFile);
        }
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
     * @throws MojoFailureException in case an IOException occurred
     */
    void writeServiceClass() throws MojoFailureException {
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

        // open template file input stream and java source file output stream for generated service file
        try (InputStream inStream = createServiceTemplateResourceStream();
             OutputStream outputStream = createServiceClassOutputStream(packageDir)) {
            // input stream should always be available from resource file
            if (inStream == null) {
                throw new MojoFailureException("Failed to read service template from plugin resources");
            }

            // create reader and writer objects for template and output files respectively
            try (Reader reader = new InputStreamReader(inStream, StandardCharsets.UTF_8);
                 PrintWriter writer = new PrintWriter(outputStream)) {

                // read template file to string builder
                String out = readTemplateFile(reader);

                // iterate map of template values for replacement
                for (Map.Entry<String, String> entry : getTemplateValues().entrySet()) {
                    // replace values in string
                    out = out.replace("${" + entry.getKey() + "}", entry.getValue());
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
     * @return map of template variable replacement values
     */
    Map<String, String> getTemplateValues() {
        Map<String, String> valueMap = new HashMap<>();
        valueMap.put("package", packageName);
        valueMap.put("serviceClass", serviceClass);
        valueMap.put("groupId", project.getGroupId());
        valueMap.put("artifactId", project.getArtifactId());
        valueMap.put("version", project.getVersion());
        valueMap.put("name", project.getName());
        valueMap.put("timestamp", "" + new Date().getTime());
        return valueMap;
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
     * {@link #writeServiceManifest()} method.
     *
     * @param serviceFile the service file name
     * @return new output stream object
     * @throws FileNotFoundException in case the file can not be created
     */
    protected OutputStream createServiceFileOutputStream(File serviceFile) throws FileNotFoundException {
        return new FileOutputStream(serviceFile);
    }

    /**
     * Create service class output stream.
     * @param packageDir the package directory
     * @return a new output stream object
     * @throws FileNotFoundException in case the file can not be created
     */
    protected OutputStream createServiceClassOutputStream(File packageDir) throws FileNotFoundException {
        return new FileOutputStream(new File(packageDir, serviceClass + ".java"));
    }

    /**
     * Get service class template resource as input stream.
     * @return the input stream
     */
    protected InputStream createServiceTemplateResourceStream() {
        return this.getClass().getResourceAsStream("service-template.txt");
    }
}
