# Accepted next goal — original GPU behavior

Accepted by the user on 2026-09-30.

## Full accepted direction

Original GPU algorithm and execution cadence, with minimal adaptations required for the newer Minecraft/Fabric renderer and Intel GPU. Not a new cloud animation technique. Preserve the full quality-port objective and the existing master-plan scope.

The user's implementation instruction:

> ok onda neka to bude sljedeci cilj commitaj sada kako je i kreni onda brisati cpu put ako nije ni bio u originalu zelim da radi kao u originalu znaci brisi sta trebas da to postignes cilje je napraviti kvalitetan port tako da ako te cpu koci brisi

## Required order and boundaries

1. Save the current state in a reviewed Git commit before removing the replacement CPU rendering path. No push requested.
2. Make the production cloud generator faithfully follow the original GPU compute algorithm and scheduling, adapting only required Minecraft/Fabric GPU-buffer/shader/render-stage integration.
3. Remove the substitute CPU generation path and its artificial motion constraints where they diverge from the original; trace shared dependencies before removal so noise/model/weather/editor functionality required by the original remains implemented.
4. Validate measured refresh cadence and visual movement against the original, including Intel, weather, transparency, shadows, world coverage, camera movement and full-pack compatibility. Build success alone does not prove parity.
5. Preserve user data, unrelated work and the ordinary installed JAR during development tests. Retain memory-guarded testing.

This replaces the earlier recommendation to keep CPU generation as a production fallback. A reversible development checkpoint is not authorization to retain a divergent final architecture.
