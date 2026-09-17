# Step 7 — Build Distant Horizons for Minecraft 26.3 from source

Part of the MASTER plan `/home/jan/simple-clouds-fabric/plans/2026-09-17-MASTER-clouds-storm-26.3.md`
(read its hard rules first). Accepted by Jan on 2026-09-17.
Source: `https://gitlab.com/distant-horizons-team/distant-horizons` (open source). Local clone:
`/home/jan/src/distant-horizons`. **Never push. Local branch `local/mony-26.3` only.**

## Facts (checked 2026-09-17)

- Released DH on Modrinth stops at `3.2.0-b-26.2` (Minecraft 26.2 only).
- DH `main` is `3.2.1-b-dev` (API `7.1.0`) and already contains `versionProperties/26.3.0.properties`;
  commit on 2026-09-16: "Fix Iris shader rendering for MC 26.3". So 26.3 needs a BUILD, probably not a port.
- Iris `1.11.6+mc26.3` refuses DH `<= 3.2.0`; a 3.2.1 build passes that check.
- Build system: Gradle with a Manifold preprocessor; Minecraft versions are selected by
  `versionProperties/<mc>.properties`; the core code is a git submodule (`coreSubProjects`) — clone
  with `--recurse-submodules`. The README has the build commands ("example commands" were updated on
  2026-09-17 — read them).

## Steps

1. Check memory (`MemAvailable` ≥ 12 GB) and that no dev client / other build runs.
2. `git clone --recurse-submodules https://gitlab.com/distant-horizons-team/distant-horizons.git /home/jan/src/distant-horizons`
   `git -C /home/jan/src/distant-horizons switch -c local/mony-26.3`. Record the `main` commit hash
   and the submodule commit in the REPORT. Copy this plan file into `/home/jan/src/distant-horizons/plans/`.
3. Read `Readme.md` (build section) and `settings.gradle`/`build.gradle` to find: the JDK it needs
   (use the Nix temurin JDK of that version if the foojay download fails), how to select the
   Minecraft version (e.g. a `-PmcVer=26.3.0` property), and which task builds the Fabric jar.
4. Build ONLY Fabric for 26.3, in the background, with `--max-workers=8`; log to a file.
5. If it fails: read the first real error. Fix only what 26.3 needs (a missing mapping/API change),
   in the smallest way, committed on `local/mony-26.3`. Two serious failed attempts → write the
   blocker in the MASTER plan and REPORT, notify, and stop this step (`[!]`).
6. Result: the Fabric jar path, its `fabric.mod.json` (`"minecraft"` range must allow 26.3), sha256,
   and the API jar/version it contains. Copy the jar to `/home/jan/.cache/simpleclouds/dh-26.3/`
   (create the folder) with the commit in the file name, e.g.
   `DistantHorizons-3.2.1-b-dev-26.3-<shortcommit>.jar`. Write all of it into the REPORT.
7. Quick smoke test is done in Simple Clouds step 7b (dev client with DH). Do NOT put the jar into
   Jan's real profile.

## Acceptance

- A DH Fabric jar for 26.3 exists in `/home/jan/.cache/simpleclouds/dh-26.3/` with recorded commit,
  sha256 and API version.

## Then

Go back to the MASTER plan: tick Step 7 (date, DH commit, jar sha256), commit + push the MASTER file
(in simple-clouds-fabric), notify, continue with **Step 7b** in
`/home/jan/simple-clouds-fabric/plans/2026-09-17-simple-clouds-storm-parity-26.3.md`.
