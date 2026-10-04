# Simple Clouds 26.3 — source and dependency notices

This is an unofficial personal-use Fabric port, not an original-author release.
These notices describe bundled components; they do not relicense them or grant
permission for public distribution. Public publishing is not part of this handoff.

## Base code

Simple Clouds and CrackersLib are by nonamecrackers2. Preserve the complete
PolyForm Perimeter 1.0.1 terms in LICENSE.md and individual source-file notices.
Original sources: https://github.com/nonamecrackers2/simple-clouds and
https://github.com/nonamecrackers2/crackerslib . GLSL files with separate notices
keep those notices; the project README lists the original exceptions.

## Optional integrated weather build

These are independent, source-built Fabric modules nested without changing their
mod IDs or upstream class namespaces. Base-port mixins implement local storm,
wind, ownership and cleanup adapters; upstream JAR classes are not patched.

| Component | Source revision | License |
| --- | --- | --- |
| Particle Rain, PigCart | ff0e693a11a38fad454be72527ef605484e61c69 | MIT |
| Immersive Storms, TheDeathlyCow | 157767f062f464678f96af3c104def5c1f721ac3 | LGPL-3.0 |
| YetAnotherConfigLib, isXander | Maven 3.9.7+26.3-fabric | See nested upstream JAR license notices |

Exact Particle Rain and Immersive Storms source archives accompany the private
handoff bundle. Complete MIT/LGPL/GPL texts are included in the integrated JAR at
META-INF/licenses/simpleclouds-weather. Build and replacement instructions are
in WEATHER-SOURCE-AND-RELINKING.md; no binary-patching tool is needed.

## Other nested dependencies

night-config core/toml 3.8.1 (The ElectronWill) and Apache Maven Artifact 3.9.9
retain their upstream JARs, embedded license texts and notices. Minecraft,
Fabric API/Loader, Sodium, Distant Horizons and other modpack mods are runtime
dependencies, not copied into the source bundle by this release helper.
