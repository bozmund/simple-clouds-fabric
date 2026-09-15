# Accepted continuation — 2026-09-15

User accepted: continue from Claude's handoff; first fix reliability of visual
tests, then investigate the white S4 view and whole-frame lightning flashes.
Keep the currently installed Minecraft JAR unchanged.

1. Reassess the two failed terrain-gate attempts using live candidate code.
   Test only terrain actually inside the camera frustum; use a settled fallback
   for views without terrain and monotonic timing for the timeout.
2. Build the isolated candidate and run STORM visual evidence, expecting all 64
   captures, including loaded terrain in S5-01 and valid S3/S4 captures.
3. Compare STORM, NOFOG, OVL0, FOGDEBUG and NOFLASH evidence as needed to isolate
   white S4 and whole-frame flashes. Do not accept compilation as visual proof.
4. Record changes, verified findings, uncertainty and remaining validation.

Work only in the candidate copy, preserve Claude's changes and unrelated work.
Do not stop the real game. No Git mutations or real-profile installation.
