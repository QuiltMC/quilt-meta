package org.quiltmc;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.net.URL;
import java.util.*;

public class MavenRepository {
    public final String url;

    public MavenRepository(String url) {
        this.url = url;
    }

    public ArtifactMetadata getMetadata(String group, String name) throws IOException {
        Collection<String> versions = readVersionsFromPom(String.format("%s%s/%s/maven-metadata.xml",
                this.url,
                String.join("/", group.split("\\.")),
                name
        ));

        return new ArtifactMetadata(group, name, versions);
    }

    private static Collection<String> readVersionsFromPom(String path) throws IOException {
        Collection<String> versions = new LinkedHashSet<>();

        try {
            URL url = new URL(path);
            XMLStreamReader reader = XMLInputFactory.newInstance().createXMLStreamReader(url.openStream());

            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.getLocalName().equals("version")) {
                    String text = reader.getElementText();
                    versions.add(text);
                }
            }

            reader.close();
            List<String> list = new ArrayList<>(versions);
            Collections.reverse(list);
            versions.clear();
            versions.addAll(list);

            return versions;
        } catch (IOException | XMLStreamException e){
            throw new IOException("Failed to load " + path, e);
        }
    }

    public class ArtifactMetadata implements Iterable<ArtifactMetadata.Artifact> {
        public final String group;
        public final String name;
        private final Collection<String> versions;
        private final Collection<Artifact> artifacts;

        ArtifactMetadata(String group, String name, Collection<String> versions) {
            this.group = group;
            this.name = name;
            this.versions = versions;
            this.artifacts = new LinkedHashSet<>();

            for (String version : versions) {
                this.artifacts.add(new Artifact(version));
            }
        }

        @Override
        public Iterator<Artifact> iterator() {
            return this.artifacts.iterator();
        }

        public boolean contains(String version) {
            return this.versions.contains(version);
        }

        public class Artifact {
            public final String version;

            public Artifact(String version) {
                this.version = version;
            }

            public String mavenId() {
                return String.format("%s:%s:%s", ArtifactMetadata.this.group, ArtifactMetadata.this.name, this.version);
            }

            public String url() {
                return String.format("%s%s/%s/%s/%s-%s.jar",
                        MavenRepository.this.url,
                        ArtifactMetadata.this.group.replaceAll("\\.", "/"),
                        ArtifactMetadata.this.name,
                        this.version,
                        ArtifactMetadata.this.name,
                        this.version
                );
            }
        }
    }
}
