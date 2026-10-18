package dev.keelfy.provenance;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import lombok.val;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/** @author keelfy */
public final class ProvenanceLoader implements PluginLoader {

    private static final String GUICE_VERSION = "7.0.0";

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
        builder.addLibrary(resolver);
    }
}
