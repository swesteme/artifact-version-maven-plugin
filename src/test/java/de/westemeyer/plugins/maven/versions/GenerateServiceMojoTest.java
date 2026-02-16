package de.westemeyer.plugins.maven.versions;

import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerateServiceMojoTest {

    private static final String TEMPLATE = "\"${groupId}\":\"${artifactId}\":\"${version}\":\"${name}\":\"${package}\":\"${serviceClass}\":\"${url}\":\"${description}\":\"${parentArtifactDefinition}\"";


    @ParameterizedTest(name = "{0}")
    @CsvSource({"Regular file,false,false,false", "Existing directory,true,false,true", "New directory,false,true,true"})
    void makeDirectories(String name, boolean isDirectory, boolean canMakeDirectory, boolean success) {
        // given
        GenerateServiceMojo mojo = new GenerateServiceMojo();
        File mockFile = mock(File.class);
        when(mockFile.isDirectory()).thenReturn(isDirectory);
        when(mockFile.mkdirs()).thenReturn(canMakeDirectory);

        // when
        Executable executable = () -> mojo.makeDirectories(mockFile, name);

        // then
        if (success) {
            assertDoesNotThrow(executable);
        } else {
            assertThrows(MojoFailureException.class, executable);
        }
    }

    @Test
    void readTemplateFile() throws IOException {
        // given
        String input = "This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. ";
        GenerateServiceMojo mojo = new GenerateServiceMojo();

        // when
        String actual = mojo.readTemplateFile(new StringReader(input));

        // then
        assertEquals(input, actual);
    }

    @Test
    void getTemplateValues() {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        mojo.serviceClass = "MyServiceClass";
        mojo.packageName = "de.westemeyer.service.version";
        MavenProject project = getMavenProject("de.westemeyer", "artifact-version-test", "1.0.0-SNAPSHOT");
        MavenProject parentProject = getMavenProject("de.westemeyer.parent", "artifact-version-test-parent", "1.0.0");
        when(project.getParent()).thenReturn(parentProject);
        when(project.getName()).thenReturn("My new maven\nproject name");
        when(project.getUrl()).thenReturn("URL");
        when(project.getDescription()).thenReturn("Description");
        mojo.project = project;
        when(mojo.getTemplateValues(anyString())).thenCallRealMethod();
        when(mojo.getParentArtifactDefinition()).thenCallRealMethod();

        // when
        Map<String, String> templateValues = mojo.getTemplateValues("ConfigClass");

        // then
        assertNotNull(templateValues);
        assertEquals("de.westemeyer.service.version", templateValues.get("package"));
        assertEquals("MyServiceClass", templateValues.get("serviceClass"));
        assertEquals("de.westemeyer", templateValues.get("groupId"));
        assertEquals("My new maven\\nproject name", templateValues.get("name"));
        assertEquals("URL", templateValues.get("url"));
        assertEquals("artifact-version-test", templateValues.get("artifactId"));
        assertEquals("1.0.0-SNAPSHOT", templateValues.get("version"));
        assertEquals("Description", templateValues.get("description"));
        assertEquals(
                "        BasicArtifact parentArtifact = new BasicArtifact(\"de.westemeyer.parent\", \"artifact-version-test-parent\", \"1.0.0\", null);\n",
                templateValues.get("parentArtifactDefinition"));
        assertDoesNotThrow(() -> Long.valueOf(templateValues.get("timestamp")));
    }

    private static MavenProject getMavenProject(String groupId, String artifactId, String version) {
        MavenProject project = mock(MavenProject.class);
        when(project.getGroupId()).thenReturn(groupId);
        when(project.getArtifactId()).thenReturn(artifactId);
        when(project.getVersion()).thenReturn(version);
        return project;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"artifact-version-test,ArtifactVersionTestVersionService",
            "artifact-id-string,ArtifactIdStringVersionService",
            "artifact.id.string,ArtifactIdStringVersionService",
            "artifactidstring,ArtifactidstringVersionService"})
    void determineServiceClassName(String input, String expected) {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        MavenProject project = mojo.project;
        when(project.getArtifactId()).thenReturn(input);
        when(mojo.determineServiceClassName()).thenCallRealMethod();
        // when
        String actual = mojo.determineServiceClassName();
        // then
        assertEquals(expected, actual);
    }

    public static Stream<Arguments> exceptionMethodSource() {
        return Stream.of(Arguments.of(new SecurityException()), Arguments.of(new IllegalArgumentException()),
                Arguments.of(new UnsupportedOperationException()), Arguments.of(new IOException()));
    }

    @ParameterizedTest
    @MethodSource("exceptionMethodSource")
    void writeServiceManifestCreateOutputStreamFails(Throwable throwable) throws IOException, MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeServiceManifest(anyString(), anyString(), anyString());
        when(mojo.createServiceFileOutputStream(any(File.class))).thenThrow(throwable);
        // when/then
        assertThrows(MojoFailureException.class,
                () -> mojo.writeServiceManifest("services", "de.westemeyer.version.core.api.ArtifactVersionService",
                        mojo.packageName + "." + mojo.serviceClass));
    }

    @Test
    void writeServiceManifestCreatePrintWriterFails() throws MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeServiceManifest(anyString(), anyString(), anyString());
        when(mojo.createPrintWriter(null)).thenThrow(new SecurityException());
        // when/then
        assertThrows(MojoFailureException.class,
                () -> mojo.writeServiceManifest("services", "de.westemeyer.version.core.api.ArtifactVersionService",
                        mojo.packageName + "." + mojo.serviceClass));
    }

    @Test
    void writeServiceManifestCloseWriterFails() throws MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeServiceManifest(anyString(), anyString(), anyString());
        PrintWriter printWriter = mock(PrintWriter.class);
        when(mojo.createPrintWriter(null)).thenReturn(printWriter);
        doThrow(SecurityException.class).when(printWriter).close();
        // when/then
        assertThrows(MojoFailureException.class,
                () -> mojo.writeServiceManifest("services", "de.westemeyer.version.core.api.ArtifactVersionService",
                        mojo.packageName + "." + mojo.serviceClass));
    }

    @Test
    void writeServiceManifestPrintlnWriterFails() throws MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeServiceManifest(anyString(), anyString(), anyString());
        PrintWriter printWriter = mock(PrintWriter.class);
        when(mojo.createPrintWriter(null)).thenReturn(printWriter);
        doThrow(SecurityException.class).when(printWriter).println(anyString());
        // when/then
        assertThrows(MojoFailureException.class,
                () -> mojo.writeServiceManifest("services", "de.westemeyer.version.core.api.ArtifactVersionService",
                        mojo.packageName + "." + mojo.serviceClass));
    }

    @Test
    void writeServiceManifestSuccessfully() throws IOException, MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeServiceManifest(anyString(), anyString(), anyString());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(mojo.createServiceFileOutputStream(any(File.class))).thenReturn(outputStream);
        when(mojo.createPrintWriter(any())).thenCallRealMethod();
        // when
        assertDoesNotThrow(() -> mojo.writeServiceManifest("services", "fileName", "content"));
        // then
        assertEquals("content", outputStream.toString().replace("\n", ""));
    }

    @Test
    void writeClassFileSuccessfully() throws MojoFailureException, IOException {
        // given
        File packageDir = new File("de/westemeyer");
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeClassFile(anyString(), anyMap(), anyString());
        mojo.packageName = "de.westemeyer";
        when(mojo.createServiceTemplateResourceStream(anyString())).thenReturn(
                new ByteArrayInputStream(TEMPLATE.getBytes(
                        StandardCharsets.UTF_8)));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(mojo.createClassFileOutputStream(packageDir, "MyServiceClass.java")).thenReturn(outputStream);
        when(mojo.readTemplateFile(any(Reader.class))).thenCallRealMethod();
        Map<String, String> templateValues = getSimpleTemplateValues();
        when(mojo.createPrintWriter(any(OutputStream.class))).thenCallRealMethod();
        // when
        assertDoesNotThrow(() -> mojo.writeClassFile("file", templateValues, "MyServiceClass"));
        // then
        verify(mojo).makeDirectories(packageDir, "service class package");
        assertEquals(
                "\"MyGroupId\":\"MyArtifactId\":\"MyVersion\":\"MyName\":\"MyPackage\":\"MyServiceClass\":\"MyUrl\":\"MyDescription\":null",
                outputStream.toString());
    }

    private static Map<String, String> getSimpleTemplateValues() {
        Map<String, String> templateValues = new HashMap<>();
        templateValues.put("serviceClass", "MyServiceClass");
        templateValues.put("groupId", "MyGroupId");
        templateValues.put("artifactId", "MyArtifactId");
        templateValues.put("version", "MyVersion");
        templateValues.put("name", "MyName");
        templateValues.put("package", "MyPackage");
        templateValues.put("url", "MyUrl");
        templateValues.put("description", "MyDescription");
        templateValues.put("parentArtifactDefinition", null);
        return templateValues;
    }

    @Test
    void writeClassFileNullInputStream() throws MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeClassFile("file", null, "MyServiceClass");
        mojo.packageName = "de.westemeyer";
        // when
        MojoFailureException mojoFailureException = assertThrows(MojoFailureException.class,
                () -> mojo.writeClassFile("file", null, "MyServiceClass"));
        // then
        assertEquals("Failed to read service template from plugin resources", mojoFailureException.getMessage());
    }

    @Test
    void writeClassFileClosingPrintWriterFails() throws MojoFailureException, IOException {
        // given
        File packageDir = new File("de/westemeyer");
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeClassFile(anyString(), anyMap(), anyString());
        mojo.packageName = "de.westemeyer";
        when(mojo.createServiceTemplateResourceStream(anyString())).thenReturn(
                new ByteArrayInputStream(TEMPLATE.getBytes(
                        StandardCharsets.UTF_8)));
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(mojo.createClassFileOutputStream(packageDir, "MyServiceClass.java")).thenReturn(outputStream);
        when(mojo.readTemplateFile(any(Reader.class))).thenCallRealMethod();
        PrintWriter printWriter = mock(PrintWriter.class);
        when(mojo.createPrintWriter(any(OutputStream.class))).thenReturn(printWriter);
        Map<String, String> templateValues = getSimpleTemplateValues();
        doThrow(IOException.class).when(printWriter).close();
        // when
        assertThrows(MojoFailureException.class, () -> mojo.writeClassFile("file", templateValues, "MyServiceClass"));
        // then
        verify(mojo).makeDirectories(packageDir, "service class package");
    }

    @Test
    void writeClassFileCreatingOutputStreamFails() throws MojoFailureException, IOException {
        // given
        File packageDir = new File("de/westemeyer");
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).writeClassFile("file", null, "MyServiceClass");
        mojo.packageName = "de.westemeyer";
        when(mojo.createServiceTemplateResourceStream(anyString())).thenReturn(
                new ByteArrayInputStream(TEMPLATE.getBytes(
                        StandardCharsets.UTF_8)));
        when(mojo.createClassFileOutputStream(packageDir, "MyServiceClass.java")).thenThrow(IOException.class);
        // when
        assertThrows(MojoFailureException.class, () -> mojo.writeClassFile("file", null, "MyServiceClass"));
        // then
        verify(mojo).makeDirectories(packageDir, "service class package");
    }

    @Test
    void getParentArtifactDefinition() {
        // given
        GenerateServiceMojo mock = getServiceMojoMock();
        when(mock.getParentArtifactDefinition()).thenCallRealMethod();
        MavenProject project = mock.project;
        MavenProject grandParentProject = getMavenProject("grandParentGroupId", "grandParentArtifactId",
                "grandParentVersion");
        MavenProject parentProject = getMavenProject("parentGroupId", "parentArtifactId", "parentVersion");
        when(project.getParent()).thenReturn(parentProject);
        when(parentProject.getParent()).thenReturn(grandParentProject);
        // when
        String parentArtifactDefinition = mock.getParentArtifactDefinition();
        // then
        assertEquals(
                "        BasicArtifact parentArtifact1 = new BasicArtifact(\"grandParentGroupId\", \"grandParentArtifactId\", \"grandParentVersion\", null);\n" +
                        "        BasicArtifact parentArtifact = new BasicArtifact(\"parentGroupId\", \"parentArtifactId\", \"parentVersion\", parentArtifact1);\n",
                parentArtifactDefinition);
    }

    @Test
    void getParentArtifactDefinitionWithoutParent() {
        // given
        GenerateServiceMojo mock = getServiceMojoMock();
        when(mock.getParentArtifactDefinition()).thenCallRealMethod();
        // when
        String parentArtifactDefinition = mock.getParentArtifactDefinition();
        // then
        assertEquals("", parentArtifactDefinition);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"service-template.txt,NATIVE,false",
            "service-template-spring-boot.txt,SPRING_BOOT,false",
            "service-template-spring-boot-with-parent.txt,SPRING_BOOT,true",
            "service-template-with-parent.txt,NATIVE,true"})
    void getTemplateResourceFileName(String expected, ServiceType serviceType, boolean hasParent) {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        when(mojo.getTemplateResourceFileName()).thenCallRealMethod();
        mojo.serviceType = serviceType;
        if (hasParent) {
            MavenProject project = mojo.project;
            when(project.getParent()).thenReturn(project);
        }
        // when
        String templateResourceFileName = mojo.getTemplateResourceFileName();
        // then
        assertEquals(expected, templateResourceFileName);
    }

    @Test
    void setUpParameterValue() {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        when(mojo.setUpParameterValue(anyString(), any(), any())).thenCallRealMethod();
        Log log = mock(Log.class);
        when(mojo.getLog()).thenReturn(log);
        // when
        assertEquals("value", mojo.setUpParameterValue("desc", "value", null));
        assertEquals("value", mojo.setUpParameterValue("desc", null, () -> "value"));
        assertEquals("value", mojo.setUpParameterValue("desc", "", () -> "value"));
        // then
        verify(log, times(2)).info(anyString());
    }

    public static Stream<Arguments> packagingSource() {
        return Stream.of(Arguments.of("pom", 0), Arguments.of("jar", 1), Arguments.of("any-other-packaging", 1),
                () -> new Object[]{null, 1});
    }

    @ParameterizedTest
    @MethodSource("packagingSource")
    void execute(String packaging, int times) throws MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        doCallRealMethod().when(mojo).execute();
        doNothing().when(mojo).generateFiles();
        MavenProject project = mojo.project;
        when(project.getPackaging()).thenReturn(packaging);
        // when
        assertDoesNotThrow(mojo::execute);
        // then
        verify(mojo, times(times)).generateFiles();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"Native Java service,NATIVE,services,de.westemeyer.version.core.api.ArtifactVersionService,de.westemeyer.versions.ArtifactVersionsVersionService,true",
            "Spring Boot service w/ AutoConfiguration,SPRING_BOOT,spring,org.springframework.boot.autoconfigure.AutoConfiguration.imports,de.westemeyer.versions.ArtifactVersionsAutoConfiguration,false",
            "Spring Boot service w/o AutoConfiguration,SPRING_BOOT,spring,org.springframework.boot.autoconfigure.AutoConfiguration.imports,de.westemeyer.versions.ArtifactVersionsAutoConfiguration,true"
    })
    void generateFiles(String name, ServiceType type, String subDirectory, String fileName,
                       String fileContent, boolean skipAutoConfiguration) throws MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        mojo.project = getMavenProject("de.westemeyer", "artifact-versions", "parentVersion");
        when(mojo.getLog()).thenReturn(mock(Log.class));
        when(mojo.getTemplateResourceFileName()).thenReturn("template");
        Map<String, String> mockTemplateValues = new HashMap<>();
        when(mojo.getTemplateValues("ArtifactVersionsAutoConfiguration")).thenReturn(mockTemplateValues);
        mojo.serviceType = type;
        mojo.targetFolder = new File("folder");
        mojo.skipSpringBootAutoConfiguration = skipAutoConfiguration;
        when(mojo.setUpParameterValue(anyString(), any(), any())).thenCallRealMethod();
        when(mojo.determineServiceClassName()).thenCallRealMethod();
        when(mojo.determineAutoConfigClassName()).thenCallRealMethod();
        doCallRealMethod().when(mojo).generateFiles();
        // when
        mojo.generateFiles();
        // then
        verify(mojo).writeClassFile("template", mockTemplateValues, "ArtifactVersionsVersionService");
        verify(mojo.project).addCompileSourceRoot("folder");
        verify(mojo, times(type == ServiceType.NATIVE || !skipAutoConfiguration ? 1 : 0)).writeServiceManifest(
                subDirectory, fileName, fileContent);
        if (type.equals(ServiceType.SPRING_BOOT) && !skipAutoConfiguration) {
            verify(mojo).writeClassFile("service-template-spring-boot-configuration.txt", mockTemplateValues,
                    "ArtifactVersionsAutoConfiguration");
        }
    }

    @ParameterizedTest
    @CsvSource({"abc,def,SPRING_BOOT,abc,def",
            "abc,abc,SPRING_BOOT,abc,abcAutoConfiguration",
            "abc,abc,NATIVE,abc,abc",
            "abcVersionService,autoConfig,SPRING_BOOT,abcVersionService,autoConfig",
            "AbcVersionService,,SPRING_BOOT,AbcVersionService,AbcAutoConfiguration",
            "abc,,SPRING_BOOT,abc,abcAutoConfiguration",
            ",abc,SPRING_BOOT,ArtifactVersionsVersionService,abc",
            ",,SPRING_BOOT,ArtifactVersionsVersionService,ArtifactVersionsAutoConfiguration"})
    void generateFilesCheckClassNames(String serviceClass, String autoConfigClass,
                                      ServiceType serviceType, String expectedServiceClass,
                                      String expectedAutoConfigClass) throws MojoFailureException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        mojo.project = getMavenProject("de.westemeyer", "artifact-versions", "parentVersion");
        mojo.serviceClass = serviceClass;
        mojo.autoConfigurationClass = autoConfigClass;
        when(mojo.getLog()).thenReturn(mock(Log.class));
        when(mojo.getTemplateResourceFileName()).thenReturn("template");
        Map<String, String> mockTemplateValues = new HashMap<>();
        when(mojo.getTemplateValues(autoConfigClass)).thenReturn(mockTemplateValues);
        mojo.serviceType = serviceType;
        mojo.targetFolder = new File("folder");
        when(mojo.setUpParameterValue(anyString(), any(), any())).thenCallRealMethod();
        when(mojo.determineServiceClassName()).thenCallRealMethod();
        when(mojo.determineAutoConfigClassName()).thenCallRealMethod();
        doCallRealMethod().when(mojo).generateFiles();

        // when
        mojo.generateFiles();

        // then
        assertEquals(expectedServiceClass, mojo.serviceClass);
        assertEquals(expectedAutoConfigClass, mojo.autoConfigurationClass);
    }

    @Test
    void checkFileExistsCaseSensitive() throws IOException {
        // given
        File file = mock(File.class);
        GenerateServiceMojo mojo = getServiceMojoMock();
        when(mojo.checkFileExistsCaseSensitive(any(File.class), anyString())).thenCallRealMethod();
        when(file.getCanonicalFile()).thenReturn(file);
        when(file.getName()).thenReturn("MyCamelCaseName");
        // when/then
        assertTrue(mojo.checkFileExistsCaseSensitive(file, file.getName()));
        assertFalse(mojo.checkFileExistsCaseSensitive(file, "otherFileName.java"));
    }

    @Test
    void checkFileExistsCaseSensitiveFails() throws IOException {
        // given
        File file = mock(File.class);
        GenerateServiceMojo mojo = getServiceMojoMock();
        when(mojo.checkFileExistsCaseSensitive(any(File.class), anyString())).thenCallRealMethod();
        when(file.getCanonicalFile()).thenThrow(IOException.class);
        // when
        boolean checkFileExistsCaseSensitive = mojo.checkFileExistsCaseSensitive(file, "otherFileName.java");
        // then
        assertFalse(checkFileExistsCaseSensitive);
    }

    public static Stream<Arguments> lineFeedInput() {
        return Stream.of(Arguments.of("Only line feed", "my\nline", "my\\nline"),
                Arguments.of("Carriage return and line feed", "my\r\nline", "my\\nline"),
                Arguments.of("Tab", "my\tline", "my line"),
                Arguments.of("Null input", null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lineFeedInput")
    void replaceLineFeeds(String name, String input, String expected) {
        assertEquals(expected, GenerateServiceMojo.replaceLineFeeds(input));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"Valid resource,service-template.txt,true", "Invalid resource,no-service-template.txt,false"})
    void createServiceTemplateResourceStream(String name, String resourceName, boolean expected) throws IOException {
        try (InputStream stream = new GenerateServiceMojo().createServiceTemplateResourceStream(resourceName)) {
            assertEquals(expected, stream != null);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"No file and no duplicate,false,false,false,0,0",
            "File and duplicate exist,true,true,false,0,0",
            "Delete file,true,false,false,1,1",
            "Invalid path,true,false,true,1,1"})
    void cleanupExistingFile(String name, boolean fileExists, boolean checkFileExistsCaseInsensitive,
                             boolean invalidPath, int toPathCalledExpectation, int getLogCalledExpectation) {
        // given
        File file = mock(File.class);
        String fileName = "fileName";
        GenerateServiceMojo mojo = getServiceMojoMock();
        when(mojo.checkFileExistsCaseSensitive(file, fileName)).thenReturn(checkFileExistsCaseInsensitive);
        when(mojo.getLog()).thenReturn(mock(Log.class));
        when(file.exists()).thenReturn(fileExists);
        doCallRealMethod().when(mojo).cleanupExistingFile(file, fileName);
        if (invalidPath) {
            when(file.toPath()).thenThrow(InvalidPathException.class);
        } else {
            when(file.toPath()).thenReturn(new File("a.txt").toPath());
        }

        // when
        mojo.cleanupExistingFile(file, fileName);

        // then
        verify(mojo, times(getLogCalledExpectation)).getLog();
        verify(file, times(toPathCalledExpectation)).toPath();
    }

    @Test
    void createClassFileOutputStreamFails() throws IOException {
        // given
        GenerateServiceMojo mojo = getServiceMojoMock();
        File packageDir = new File("/a/b/c");
        String fileName = "fileName";
        when(mojo.createClassFileOutputStream(packageDir, fileName)).thenCallRealMethod();
        // when
        assertThrows(IOException.class, () -> {
            try (OutputStream stream = mojo.createClassFileOutputStream(packageDir,
                    fileName)) {
                fail();
            }
        });
        // then
        verify(mojo).cleanupExistingFile(any(File.class), eq(fileName));
    }

    @Test
    void createServiceFileOutputStream() throws IOException {
        try (OutputStream stream = new GenerateServiceMojo().createServiceFileOutputStream(
                new File("target/abc.txt"))) {
            assertNotNull(stream);
        }
    }

    @Test
    void createServiceFileOutputStreamFails() {
        File file = mock(File.class);
        when(file.toPath()).thenThrow(InvalidPathException.class);
        assertThrows(IOException.class, () -> {
            try (OutputStream stream = new GenerateServiceMojo().createServiceFileOutputStream(file)) {
                fail("should not reach this method body");
            }
        });
    }

    private static GenerateServiceMojo getServiceMojoMock() {
        Build build = mock(Build.class);
        MavenProject project = mock(MavenProject.class);
        when(project.getBuild()).thenReturn(build);
        GenerateServiceMojo mojo = mock(GenerateServiceMojo.class);
        mojo.project = project;
        return mojo;
    }
}
