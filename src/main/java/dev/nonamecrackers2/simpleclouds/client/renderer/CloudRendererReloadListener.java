package dev.nonamecrackers2.simpleclouds.client.renderer;

import java.util.Collection;
import java.util.List;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** Resource preparation is CPU-only; GPU teardown runs on the apply executor.
 * Initial startup remains lazy until the graphics device and renderer exist. */
public final class CloudRendererReloadListener extends SimplePreparableReloadListener<Boolean>
        implements IdentifiableResourceReloadListener {
    @Override public Identifier getFabricId() { return Identifier.parse("simpleclouds:cloud_renderer"); }
    @Override public Collection<Identifier> getFabricDependencies() {
        return List.of(Identifier.parse("simpleclouds:cloud_types"),Identifier.parse("simpleclouds:cloud_spawning"));
    }
    @Override protected Boolean prepare(ResourceManager resources,ProfilerFiller profiler) { return true; }
    @Override protected void apply(Boolean prepared,ResourceManager resources,ProfilerFiller profiler) {
        SimpleCloudsRenderer.getOptionalInstance().ifPresent(renderer->{
            com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
            renderer.onResourceManagerReload(resources);
            org.slf4j.LoggerFactory.getLogger(CloudRendererReloadListener.class).info(
                "[RENDERER-RELOAD] released old generator/preview after resource data apply; next render rebuilds");
        });
        dev.nonamecrackers2.simpleclouds.client.DhFogLifecycleProbe.resourcesReloaded();
    }
}
