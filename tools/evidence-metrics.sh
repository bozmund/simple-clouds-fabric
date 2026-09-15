#!/usr/bin/env bash
set -euo pipefail
cd /home/jan/.cache/simpleclouds/port-candidate-tgkFnnmU
nix shell --impure --expr '(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux.python3.withPackages (ps: [ ps.pillow ])' -c python3 tools/flash-metrics.py "$1"
nix shell --impure --expr '(builtins.getFlake "path:/home/jan/nixos-symbiote").inputs.nixpkgs.legacyPackages.x86_64-linux.python3.withPackages (ps: [ ps.pillow ])' -c python3 tools/imgstat.py "$1/devshot-S4.png"
