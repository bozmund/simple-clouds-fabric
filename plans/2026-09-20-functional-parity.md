# Accepted goal: finish Simple Clouds functional parity

Accepted by Jan on 2026-09-20: start repairing all identified incomplete port areas until finished. Reference is the preserved original 1.20.1 mod; target is the current 26.3 Fabric repository.

## Implementation scope

1. Restore precipitation selection by biome and position, snow, wind direction and angle, collision/roof handling, weather transition smoothing, original storm lifecycle, lightning and thunder behavior. Correct weather/cloud draw ordering.
2. Restore original transparency composition and assess remaining mesh/LOD parity against matched reference scenes.
3. Connect sleeping during storms and storm removal after sleeping to actual game events.
4. Restore server commands and argument serialization, config-change synchronization, and datapack reload synchronization.
5. Complete cloud preview type selection, noise layer editing, and image export.
6. Restore custom rain sound replacement and functional API event dispatch.
7. Audit remaining placeholders and settings for reachable missing behavior, distinguishing obsolete compatibility code from active defects. Harden render-pass cleanup on failure.

## Accepted additions — 2026-09-27

Jan requested integrating Particle Rain and Immersive Storms as part of this mod, not merely installing unrelated weather systems. Simple Clouds remains the authority for local cloud coverage, precipitation intensity, wind and storms. Adapt Particle Rain precipitation and Immersive Storms biome-specific fog, sandstorms, blizzards and wind to that state; prevent duplicate precipitation, conflicting fog and weather outside the affected cloud regions. Provide independent effect toggles. Inspect and preserve upstream license notices, authorship and source obligations before incorporating code. Particle Rain advertises a 26.3 source target; the inspected Immersive Storms branch targets 26.1 and requires compatibility work. These integrations are not yet implemented and follow stabilization of the base port.

Jan also requested investigating the main modded profile crash and testing the port with the other installed mods. Preserve the main profile's worlds, configuration and unrelated mods. Inspect its actual crash logs and installed JAR first, then reproduce in an isolated copy of its mod/config set with a disposable world. Keep both minimal-client tests and full-modpack compatibility tests. Do not treat a minimal-client pass as proof of modpack compatibility. No replacement of the main profile JAR or Git mutations is implied by this addition.

Jan subsequently authorized direct testing on the main profile, stating that it contains nothing important yet. Main-profile test launches and candidate installation are permitted; preserve recoverable backups and do not remove unrelated mods without a diagnostic reason. This supersedes the earlier read-only constraint for these tests, not the Git restriction.

## Validation and completion

Inspect current changes before editing; preserve Claude's implementation and unrelated work. Compare behavior with original source, not comments alone. Build and run meaningful regression tests per coherent change. Test motion, interaction, biome transitions, shelter, weather edges and multiplayer paths where practical. Use isolated development worlds and preserve existing evidence. Report untested behavior explicitly; a successful build or static screenshot does not prove parity. Finish with a traceable report, candidate JAR checksum, and outstanding limitations (if any).

## Constraints

No Git mutations without Jan explicitly requesting the exact operation. Real Modrinth profile is read-only until explicit installation authorization. Do not terminate Jan's game. One heavy build/client job at a time. No automatic deployment, profile edits, or unrelated system changes.
