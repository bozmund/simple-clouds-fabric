# User-requested gameplay test install — 2026-10-01

Jan explicitly requested installation of the latest mod in his profile for testing.

- Target: `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.3/mods/simple-clouds-0.7.3+26.3-fabric.jar`.
- Installed SHA256: `f1b4119a82216773eb43e81cce6d40e8a9dd88c13fe07bc06b2852afaea71ce2`.
- Matches the candidate in `evidence-codex-20260930-fullpack-coverage-RAINREPLAY-O4DOOM/candidate.sha256`.
- Contains the source-built integrated Particle Rain and Immersive Storms libraries and YACL.
- Previous JAR backed up at `/home/jan/.cache/simpleclouds/user-install-20261001-uxwPTf/previous.jar`, SHA256 `d77f0afca8121e31b6b9d09d8987b78646acb787b71f9eec9b3cddcfa57ae1bd`.
- No Minecraft client running at installation preflight. Atomic same-directory replacement and final checksum verified.
- No worlds, settings, other mods, model services or Git state changed. Did not launch the game.
- Development fixtures and experimental GL binding restore remain explicit environment opt-ins, not enabled for ordinary gameplay.

This supersedes earlier notes that the production profile still contains d77f0af. It is a user-authorized test installation, not final release approval: colored rain corruption remains unresolved, and the complete C00–C12 goal is not achieved. Jan owns final visual gameplay review.
