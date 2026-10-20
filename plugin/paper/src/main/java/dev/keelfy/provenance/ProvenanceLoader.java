package dev.keelfy.provenance;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import java.util.List;
import lombok.val;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;

/** @author keelfy */
public final class ProvenanceLoader implements PluginLoader {

    private static final String GUICE_VERSION = "7.0.0";
    private static final String HIKARI_VERSION = "7.1.0";
    private static final String MARIADB_VERSION = "3.5.10";
    private static final String POSTGRESQL_VERSION = "42.7.14";

    @Override
    public void classloader(final PluginClasspathBuilder builder) {
        val resolver = new MavenLibraryResolver();
        val repository = new RemoteRepository.Builder(
                        "papermc", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR)
                .build();
        if (repository == null) {
            throw new IllegalStateException("Failed to create Maven repository");
        }

        resolver.addRepository(repository);

        val guice = new DefaultArtifact("com.google.inject:guice:" + GUICE_VERSION);
        resolver.addDependency(new Dependency(guice, null));

        // Paper already provides slf4j, so HikariCP must not bring its own copy.
        val hikari = new DefaultArtifact("com.zaxxer:HikariCP:" + HIKARI_VERSION);
        resolver.addDependency(
                new Dependency(hikari, null, false, List.of(new Exclusion("org.slf4j", "slf4j-api", "*", "*"))));
        // Paper ships only the SQLite driver; the other two are fetched whether or not config.yml selects them.
        resolver.addDependency(
                new Dependency(new DefaultArtifact("org.mariadb.jdbc:mariadb-java-client:" + MARIADB_VERSION), null));
        resolver.addDependency(
                new Dependency(new DefaultArtifact("org.postgresql:postgresql:" + POSTGRESQL_VERSION), null));
        builder.addLibrary(resolver);
    }
}
