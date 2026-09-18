# MASTER PLAN — Simple Clouds storm parity, Minecraft 26.3, Distant Horizons 26.3

Accepted by Jan on 2026-09-17. Written by Claude, executed by Symbiote, reviewed by Claude.
**This file is the only progress tracker.** Detailed instructions live in one plan per repository.

## How to work through this plan

1. Find the first unchecked step in the checklist below.
2. Open the detailed plan named in that step and do exactly that step.
3. When its acceptance checks pass: tick the step here (`[x]`), add the date, commit hash(es) and
   evidence folder, commit this file, push, and send a short notification (see "Reporting").
4. Go back to 1. Never skip ahead; never do two steps at once.

If a step cannot be finished after two serious attempts, write the blocker under "Blockers" below
(what you tried, exact error, evidence path), notify Jan, mark the step `[!]`, and continue with the
next step that does not depend on it. Steps 6-8 depend on each other; 2-5 do not depend on 0.

## Machine facts (mony, NixOS, user `jan`)

- GPUs: Intel iGPU drives the display; RTX 5090 runs llama-swap (YOUR OWN MODEL — never stop,
  restart or reconfigure llama-swap); RTX 3060 is idle. Minecraft dev clients render on the display GPU.
- RAM 48 GB, **no swap**. Never run more than ONE heavy job at a time (dev client, Gradle build,
  DH build). Gradle always with `--max-workers=8`. Check `grep MemAvailable /proc/meminfo` before
  starting a heavy job; do not start one below 12 GB available.
- Long commands (> 2 min): run them in the background as a user unit with a log and an end marker:
  `systemd-run --user --unit=<name> --collect --working-directory=<dir> /run/current-system/sw/bin/bash -c '<cmd> > <log> 2>&1; echo "end $(date +%T) rc=$?" >> <log>'`
  then check the log now and then (`tail <log>`, `systemctl --user is-active <name>`). Do not block a
  tool call on a 30-minute run. (`/tmp/bg.sh` does not exist on mony.)
- Pillow for image scripts: `nix shell --impure --expr '(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux.python3.withPackages (ps: [ ps.pillow ])' -c python3 ...`
- You can look at images: read a `.png` file with the read tool. After step 0 is deployed and a new
  session is started, the `frames_sheet` / `watch_screen` / `watch_video` tools also exist.

## Repositories and places

| Place | What | Git rules |
|---|---|---|
| `/home/jan/simple-clouds-fabric` | The Simple Clouds Fabric port (GitHub `bozmund/simple-clouds-fabric`). Start branch `fix/cloud-noise-parity` (0eccfeb). | Work branch `work/storm-parity-26.3` (create from `fix/cloud-noise-parity`). One commit per verified step, push after each commit. Never force-push, never merge into `main`. |
| `/home/jan/nixos-symbiote` | NixOS config + Pi extensions. Deploy: `sudo symbiote-apply` (passwordless). | One commit for step 0 (tool + its plan). **No push.** Do not touch other uncommitted work (sprite/NPU files). |
| `/home/jan/.cache/simpleclouds/original-1.20.1` | Reference copy of the ORIGINAL mod (Forge 1.20.1), cloned from `/tmp/simple-clouds-src` (commit 9b6d468). Reference only, never distributed. | Local commits allowed, never pushed. |
| `/home/jan/src/distant-horizons` | Distant Horizons source (`gitlab.com/distant-horizons-team/distant-horizons`, branch `main`, has `versionProperties/26.3.0.properties`). | Local branch `local/mony-26.3` only. **Never push.** |
| `/home/jan/.local/share/ModrinthApp/profiles/Fabric 26.2` | **Jan's real game profile. READ-ONLY.** | Never write, launch, or install anything there. |

## Hard rules (all steps)

- Never touch Jan's real game: do not start, stop or kill any Java process outside the
  `simpleclouds-devclient` unit (or the reference/DH dev-client units you start yourself). If Jan's
  real game is running (a java process not started by you), wait — do not start a dev client.
- Never install a jar into Jan's profile. Never run `dev-relaunch.sh --install`.
- Never stop llama-swap, never run `symbiote-apply` except in step 0.
- Screenshot evidence folders: `evidence-sym-0917-NN-<short-name>` (NN increases, never reuse a name).
- A build or a passing log is NOT proof that something looks right: always look at the images.

## Checklist

- [x] **Step 0** — Video-watching tool `frame-watch` for Pi → `nixos-symbiote/plans/2026-09-17-frame-watch-tool.md` (2026-09-17, nixos-symbiote `1646c63`, deployed via `symbiote-apply --source` snapshot — see REPORT for why; evidence: `evidence-sym-0917-01-frame-watch`)
- [x] **Step 1** — DevShot capture gates fixed (STORM 64/64, SHAKE FAST 41/41) → `simple-clouds-fabric/plans/2026-09-17-simple-clouds-storm-parity-26.3.md` § Step 1 (2026-09-17, `0f455a7` on `work/storm-parity-26.3`; evidence: `evidence-sym-0917-02-storm`, `-03-shakefast`, `-04-shake` — NN continues from step 0's 01, the plan text's 01/02/03 predates it)
- [x] **Step 2** — Original 1.20.1 reference captures (superflat, same seed) → same plan § Step 2 (2026-09-18, original clone `82987c4` on `reference/devshot`, local only / never pushed; evidence: `evidence-sym-0917-05-ref-acdf` A·C·D·F 4/4, `-06-ref-storm` 64/64, `-07-ref-understorm` 41/41, `-08-ref-shake` 41/41 — all zero refusals, all looked like a working game; harness + storm-mechanics file:line + 1.20.1 world-creation gotchas in REPORT § Step 2)
- [ ] **Step 3** — Port captures of the same scenes (26.2) → same plan § Step 3
- [ ] **Step 4** — Comparison report original vs port → same plan § Step 4
- [ ] **Step 5** — Fix the differences (first: local rain level / storm darkening) → same plan § Step 5
- [ ] **Step 6** — Simple Clouds on Minecraft 26.3 → same plan § Step 6
- [ ] **Step 7** — Build Distant Horizons for 26.3 from source → `simple-clouds-fabric/plans/2026-09-17-distant-horizons-26.3-build.md` (copy it into the DH clone as `plans/` after cloning)
- [ ] **Step 7b** — Dev client always runs with the DH 26.3 build; DH compat verified → storm-parity plan § Step 7b
- [ ] **Step 8** — Full test matrix on 26.3 + DH incl. 30-min storm LOOP stress → storm-parity plan § Step 8
- [ ] **Step 9** — Final report + notify Jan and Claude → storm-parity plan § Step 9

## Reporting

- After every step: `symbiote-notify --title "Clouds plan" --message "step N done: <one line>"`.
- Running notes (what you did, commands, results, evidence paths, numbers) go into
  `/home/jan/simple-clouds-fabric/plans/2026-09-17-REPORT.md` — append, never rewrite history.
- Questions that need Jan's decision: write them under "Questions for Jan" in the REPORT, notify,
  and continue with other work.

## Blockers

(none yet)
