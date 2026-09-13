# Visual parity results (26.2 port)

Per-step evidence for VISUAL-PARITY-PLAN.md. Each step must have log lines and/or
screenshots (in `run/screenshots/devshot-*.png`, read back every time) before it is
counted as done.

Status legend: **done** = criteria met, evidence below · **partial** · **not started**

| Step | Status | Evidence |
|---|---|---|
| A1 memory (addendum) | **done** | below |
| A2 motion (addendum) | not started | — |
| A3 horizon/flat/above (addendum) | not started | — |
| 5 transparency (port) | not started | — |
| 6 shadows | not started | — |
| 7 LOD/pop-in (port) | not started | — |
| 8 lighting/darkness (port) | not started | — |

---

## A1 — memory (addendum, 2026-09-13)

**Verdict: memory levels off. Client RSS baseline ~4.5–5.0 GB with bounded transient
spikes to ~6.0 GB; no growth over 33 minutes of continuous regeneration.**

### What was wrong (2026-09-13 morning)

`CpuCloudGenerator.generate()` ran ~122 chunk generations per second (the region
signature flips every 5–20 s under drift, regenerating all 364 LOD chunks), and each
call allocated three direct buffers: the initial scratch, every grow, and the
`bound()` result copy. Direct memory only came back when the GC collected dead
buffers, so RSS climbed to 25 GB and the OOM killer took the dev client **and the
whole terminal** (the client JVM was a child of the session, not of a unit).

### What changed

1. **`ChunkBufferPool`** (new, `renderer/v2/ChunkBufferPool.java`): best-fit reuse of
   direct buffers; a buffer is only GROWN when a chunk outgrows the borrow (the grower
   releases the old one); pool retention hard-capped at 256 MB (largest free buffer
   dropped for the GC beyond the cap). `CpuCloudGenerator.generate()` now writes into
   caller-provided buffers — **zero direct allocation on the generation path** (the
   `grow()`/`bound()` copy methods are deleted; per-cell `float[3]` gradient
   allocations are a reused field).
2. **Per-chunk GPU buffers** (`SimpleCloudsRenderer`): the whole-field combined
   opaque+transparency buffers and their per-frame rebuild are gone. Each chunk owns
   one persistent `GpuBuffer` per stream (created when the chunk is published via
   the data-carrying `createBuffer` overload, closed when the chunk is replaced,
   evicted from the LOD layout, or the renderer shuts down). The draw pass is now
   one `drawIndexed` per chunk (the original's architecture); the fade-in alpha is
   just that chunk's `ColorModulator`. The shadow map draws the per-chunk buffers
   (one `drawIndexed` per chunk inside a single pass). Hard cap of 512 cached chunks
   as a safety net.
3. **Worker pool**: one `CpuCloudGenerator` per worker thread (was: one generator
   per job), buffers borrowed from the pool with ownership tracking so a failed job
   can never double-release a buffer the grower already returned.
4. **Dev client limits**: `-Xmx4G -XX:MaxDirectMemorySize=2G` (Loom `runConfigs` in
   `build.gradle`), `dev-relaunch.sh` now launches with `gradle --no-daemon`
   (client JVM stays a child of the systemd unit, never of the terminal session)
   plus `MemoryHigh=9G`/`MemoryMax=10G` on the unit and `SIMPLECLOUDS_DEV=1`.
5. **`DevMemoryLogger`** (new, `client/DevMemoryLogger.java`): 30 s heap/direct/RSS
   log lines (`[DEVMEM]`), active only with `SIMPLECLOUDS_DEV=1`.
6. **`DevShot` `LOOP` token**: cycles the standard views (A–E) until
   `devshot.request` is deleted — the 30-minute proof harness.

### Proof run (30-minute requirement)

- Launch 11:32:45, **first draw 11:32:55**, sampler 11:34:58–12:04:58 (31 samples,
  60 s), client stopped 12:08. Continuous rendering of the A–E view cycle:
  **23 view cycles in ~33 min** (each cycle re-teleports across LOD grid cells, so
  chunks kept regenerating and moving — 12,204 chunk generations in the earlier 80 s
  sanity window, ~150/s).
- **Client process RSS** (`[DEVMEM]`, 71 samples, 30 s): **avg 4.98 GB, min 4.13,
  max 6.06 GB**; first-half avg 5.02 GB, second-half avg 4.93 GB (delta **−0.09 GB**,
  i.e. flat-to-down). Direct memory oscillates 128 MB ↔ 2.0 GB on full-field
  regeneration bursts (bounded by the 364-chunk layout, not by time) and settles
  back to 100–500 MB; heap 1.0–2.4 GB against the 4 GB cap.
- **Unit cgroup** (`MemoryCurrent`, 31 samples, 60 s): avg 5.52 GB, min 4.77, max
  6.36 GB; first half 5.58 GB → second half 5.12 GB (delta **−0.46 GB**). The 10 GB
  `MemoryMax` was never approached (the cgroup number includes the ~1 GB one-shot
  Gradle daemon).
- Data: `/tmp/sc-a1-rss.csv` (cgroup), `run/logs/latest.log` grep `DEVMEM` (client),
  `/tmp/sc-a1-log.txt` (full log snapshot).
- Renders stayed correct start-to-finish: all six standard views read back in the
  first cycle (`/tmp/sc-view-{A,B,C,D,E1,E2}.png`) and again in the final cycle
  (`/tmp/sc-a1-final/devshot-*.png`, 12:06–12:08) — no pop-in artifacts, no
  degradation over the run.

**A1 criterion ("prove that its memory levels off") — MET.**
