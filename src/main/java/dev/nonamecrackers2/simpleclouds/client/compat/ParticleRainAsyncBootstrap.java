package dev.nonamecrackers2.simpleclouds.client.compat;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.nonamecrackers2.simpleclouds.common.compat.ParticleRainCancellationCompatRules;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** Optional member-level compatibility, registered before ParticleEngine is transformed.
 * Neither upstream jar is edited. Clear/lifetime hooks and other mods stay intact. */
public final class ParticleRainAsyncBootstrap implements PreLaunchEntrypoint {
    @Override public void onPreLaunch() {
        var loader=FabricLoader.getInstance();
        if(loader.getEnvironmentType()!=net.fabricmc.api.EnvType.CLIENT
                || !loader.isModLoaded("particlerain") || !loader.isModLoaded("asyncparticles")) return;
        try {
            String prefix="fun.qu_an.minecraft.asyncparticles.client.coremod.mixin_extension.member_canceller.";
            var contract=Class.forName(prefix+"MixinMemberCanceller");
            var reported=new AtomicBoolean();
            var canceller=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},(proxy,method,args)->{
                return switch(method.getName()) {
                    case "preCancel" -> ParticleRainCancellationCompatRules.selectedMixin((String)args[1]);
                    case "shouldCancelMethod" -> {
                        boolean cancel=ParticleRainCancellationCompatRules.cancelMethod((String)args[1],(String)args[3],(String)args[4]);
                        if(cancel && reported.compareAndSet(false,true)) org.apache.logging.log4j.LogManager.getLogger("simpleclouds/ParticleAsyncCompat")
                            .info("[PARTICLE-ASYNC] removed only duplicate beta100 onParticleSpawnCanceled hook; AsyncParticles owns atomic cancellation accounting");
                        yield cancel;
                    }
                    case "shouldCancelField" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy==args[0];
                    case "toString" -> "SimpleCloudsParticleRainCancellationCompat";
                    default -> throw new IllegalStateException("Unknown AsyncParticles member-canceller operation: "+method);
                };
            });
            Class.forName(prefix+"MixinMemberCancellerRegistrar").getMethod("register",contract).invoke(null,canceller);
        } catch(ReflectiveOperationException failure) {
            throw new IllegalStateException("Installed AsyncParticles member adapter changed; cannot safely adapt Particle Rain cancellation",failure);
        }
    }
}
