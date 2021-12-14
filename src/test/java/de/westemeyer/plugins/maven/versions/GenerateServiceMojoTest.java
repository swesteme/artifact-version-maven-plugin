package de.westemeyer.plugins.maven.versions;

import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.testing.stubs.MavenProjectStub;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

class GenerateServiceMojoTest {
    @Test
    void makeDirectories() {
        GenerateServiceMojo mojo = new GenerateServiceMojo();
        Assertions.assertThrows(MojoFailureException.class, () -> {
            mojo.makeDirectories(new File("/test"), "Problematic directory name");
        });
        Assertions.assertThrows(MojoFailureException.class, () -> {
            mojo.makeDirectories(new File("pom.xml"), "Regular file");
        });
        Assertions.assertThrows(MojoFailureException.class, () -> {
            mojo.makeDirectories(new File("/etc/test"), "No write access");
        });
        Assertions.assertDoesNotThrow(() -> {
            mojo.makeDirectories(new File("target"), "Existing directory");
        });
    }

    @Test
    void readTemplateFile() throws IOException {
        String input = "This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. This is my repeated input text. ";
        GenerateServiceMojo mojo = new GenerateServiceMojo();
        Assertions.assertEquals(input, mojo.readTemplateFile(new StringReader(input)));
    }

    @Test
    void getTemplateValues() {
        GenerateServiceMojo mojo = createMojo();
        Map<String, String> templateValues = mojo.getTemplateValues();
        Assertions.assertNotNull(templateValues);
        Assertions.assertEquals("de.westemeyer.service.version", templateValues.get("package"));
        Assertions.assertEquals("MyServiceClass", templateValues.get("serviceClass"));
        Assertions.assertEquals("de.westemeyer", templateValues.get("groupId"));
        Assertions.assertEquals("My new maven project name", templateValues.get("name"));
        Assertions.assertEquals("artifact-version-test", templateValues.get("artifactId"));
        Assertions.assertEquals("1.0.0-SNAPSHOT", templateValues.get("version"));
        Assertions.assertDoesNotThrow(() -> Long.valueOf(templateValues.get("timestamp")));
    }

    @Test
    void determineServiceClassName() {
        GenerateServiceMojo mojo = createMojo();
        Assertions.assertNotNull(mojo);
        Assertions.assertEquals("ArtifactVersionTestVersionService", mojo.determineServiceClassName());
        assertServiceClassName("artifact-id-string", "ArtifactIdStringVersionService");
        assertServiceClassName("artifact.id.string", "ArtifactIdStringVersionService");
        assertServiceClassName("artifactidstring", "ArtifactidstringVersionService");
    }

    @Test
    void writeServiceManifest() {
        File file = new File("target/testdir/META-INF/services/de.westemeyer.version.service.ArtifactVersionService");
        if (file.exists()) {
            Assertions.assertTrue(file.delete());
        }
        MockGenerateServiceMojo mojo = createMojo();
        Assertions.assertDoesNotThrow(mojo::writeServiceManifest);
        Assertions.assertTrue(file.isFile());
        mojo.setOutstreamBehaviour(OUTSTREAM_BEHAVIOUR.THROW);
        Assertions.assertThrows(MojoFailureException.class, mojo::writeServiceManifest);
        mojo.setOutstreamBehaviour(OUTSTREAM_BEHAVIOUR.BYTE);
        Assertions.assertDoesNotThrow(mojo::writeServiceManifest);
        Assertions.assertEquals("de.westemeyer.service.version.MyServiceClass", mojo.getOutputString().replace("\n", ""));
    }

    @Test
    void writeServiceClass() {
        File file = new File("target/testdir/de/westemeyer/service/version/MyServiceClass.java");
        if (file.exists()) {
            Assertions.assertTrue(file.delete());
        }
        MockGenerateServiceMojo mojo = createMojo();
        Assertions.assertDoesNotThrow(mojo::writeServiceClass);
        mojo.setInstreamBehaviour(INSTREAM_BEHAVIOUR.NULL);
        Assertions.assertThrows(MojoFailureException.class, mojo::writeServiceClass);

        mojo.setOutstreamBehaviour(OUTSTREAM_BEHAVIOUR.THROW);
        Assertions.assertThrows(MojoFailureException.class, mojo::writeServiceClass);

        mojo.setOutstreamBehaviour(OUTSTREAM_BEHAVIOUR.BYTE);
        mojo.setInstreamBehaviour(INSTREAM_BEHAVIOUR.BYTE);
        Assertions.assertDoesNotThrow(mojo::writeServiceClass);
        Assertions.assertEquals("\"de.westemeyer\":\"artifact-version-test\":\"1.0.0-SNAPSHOT\":\"My new maven project name\":\"de.westemeyer.service.version\":\"MyServiceClass\":\"https://www.myproject.com\":null", mojo.getOutputString().replace("\n", ""));
    }

    @Test
    void setUpParameterValue() {
        GenerateServiceMojo mojo = new GenerateServiceMojo();
        Assertions.assertEquals("value", mojo.setUpParameterValue("desc", "value", null));
        Assertions.assertEquals("value", mojo.setUpParameterValue("desc", null, () -> "value"));
        Assertions.assertEquals("value", mojo.setUpParameterValue("desc", "", () -> "value"));
    }

    @Test
    void execute() {
        MockGenerateServiceMojo mojo = createMojo();
        mojo.packageName = null;
        mojo.setInstreamBehaviour(INSTREAM_BEHAVIOUR.BYTE);
        mojo.setOutstreamBehaviour(OUTSTREAM_BEHAVIOUR.BYTE);
        Assertions.assertDoesNotThrow(mojo::execute);
        Assertions.assertTrue(mojo.project.getCompileSourceRoots().contains("target/testdir"));
    }

    private void assertServiceClassName(String artifactId, String expectedClassName) {
        MockProject project = createMockProject();
        project.setArtifactId(artifactId);
        GenerateServiceMojo mojo = new GenerateServiceMojo();
        mojo.project = project;
        Assertions.assertEquals(expectedClassName, mojo.determineServiceClassName());
    }

    private MockGenerateServiceMojo createMojo() {
        MockProject project = createMockProject();
        MockGenerateServiceMojo mojo = new MockGenerateServiceMojo();
        mojo.serviceClass = "MyServiceClass";
        mojo.packageName = "de.westemeyer.service.version";
        mojo.project = project;
        mojo.targetFolder = new File("target/testdir");
        return mojo;
    }

    private MockProject createMockProject() {
        File testPom = new File( "src/test/resources/de/westemeyer/plugins/maven/versions/pom.xml" );

        MockProject project = new MockProject();
        Build build = new Build();
        build.setOutputDirectory("target/testdir");
        project.setBuild(build);
        project.readModel(testPom);
        Model model = project.getModel();
        project.setArtifactId(model.getArtifactId());
        project.setGroupId(model.getGroupId());
        project.setName(model.getName());
        project.setVersion(model.getVersion());
        project.setUrl(model.getUrl());
        project.setDescription(model.getDescription());
        return project;
    }

    private static class MockProject extends MavenProjectStub {
        @Override
        public void readModel(File pomFile) {
            super.readModel(pomFile);
        }
    }

    private static class MockGenerateServiceMojo extends GenerateServiceMojo {
        private OUTSTREAM_BEHAVIOUR outstreamBehaviour = OUTSTREAM_BEHAVIOUR.SUPER;
        private INSTREAM_BEHAVIOUR instreamBehaviour = INSTREAM_BEHAVIOUR.SUPER;
        private final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        private static final String TEMPLATE = "\"${groupId}\":\"${artifactId}\":\"${version}\":\"${name}\":\"${package}\":\"${serviceClass}\":\"${url}\":\"${description}\"";
        private final ByteArrayInputStream inputStream = new ByteArrayInputStream(TEMPLATE.getBytes(StandardCharsets.UTF_8));

        @Override
        protected OutputStream createServiceFileOutputStream(File serviceFile) throws FileNotFoundException {
            switch (outstreamBehaviour) {
                case SUPER:
                    return super.createServiceFileOutputStream(serviceFile);
                case BYTE:
                    return byteArrayOutputStream;
                case THROW:
                default:
                    throw new FileNotFoundException("Mock file not found exception!");
            }
        }

        @Override
        protected InputStream createServiceTemplateResourceStream() {
            switch(instreamBehaviour) {
                case SUPER:
                    return super.createServiceTemplateResourceStream();
                case NULL:
                    return null;
                case BYTE:
                default:
                    return inputStream;
            }
        }

        @Override
        protected OutputStream createServiceClassOutputStream(File packageDir) throws FileNotFoundException {
            switch (outstreamBehaviour) {
                case SUPER:
                    return super.createServiceClassOutputStream(packageDir);
                case THROW:
                    throw new FileNotFoundException("Mock file not found exception!");
                case BYTE:
                default:
                    return byteArrayOutputStream;
            }
        }

        public void setOutstreamBehaviour(OUTSTREAM_BEHAVIOUR outstreamBehaviour) {
            this.outstreamBehaviour = outstreamBehaviour;
        }

        public void setInstreamBehaviour(INSTREAM_BEHAVIOUR instreamBehaviour) {
            this.instreamBehaviour = instreamBehaviour;
        }

        public String getOutputString() {
            return byteArrayOutputStream.toString();
        }
    }

    private enum INSTREAM_BEHAVIOUR {
        NULL, SUPER, BYTE
    }

    private enum OUTSTREAM_BEHAVIOUR {
        THROW, SUPER, BYTE
    }
}
