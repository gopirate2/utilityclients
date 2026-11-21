Baritone 1.19.0 for Minecraft 26.2, with UtilityClient local bug fixes.
Original source: https://github.com/cabaletta/baritone/tree/v1.19.0
Original release: https://github.com/cabaletta/baritone/releases/tag/v1.19.0
Original binary SHA-256: ba1a268856e0caee7f828b6663f26670138d5c348fdb81b8c7859809f73e8d2f
Patched binary SHA-256: 0dbe5aae8196cbcbb5fa08347878c5b7a6a9653ba8bd74d3563ac042158b759c
Patched source archive SHA-256: b9f026e54c065b0ece60a1f3a6fb4f275232273e732897e962c621c1ac265deb
License: LGPL-3.0 (LICENSE-Baritone.txt). Modification date: 2026-11-20.

UtilityClient local repairs, 2026-11-20.
Based on official Baritone v1.19.0 (Minecraft 26.2); LGPL-3.0 retained.
Modified areas: cache/executor shutdown and final flush; negative-Y special blocks, top height, flow policy and scan bounds;
waypoint/position equality and hashes; goal distance overflow and bounded arrival heuristics;
exact fill states, cylinder dimensions and nested schematic reset; empty collection settings and independent defaults;
block-removal invalidation, deferred inventory success, calculation ownership; quoted/escaped arguments,
class-based datatype peeks, file completion and explore-filter base paths.
Caches now use a new _uc2 directory suffix; existing caches are preserved.
Matching modified sources accompany every distributed binary in baritone-1.19.0-source.zip.

Fabric loads the nested pathfinder and mixins when cheatclient.jar is installed before game startup.
A runtime-attached client cannot add Baritone interfaces to already-loaded game classes; install as a Fabric mod and restart.
