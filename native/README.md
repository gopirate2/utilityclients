# ucstealth.dll

Native companion to `com.baggute.cheatclient.stealth`. Two jobs:

1. `SetFileAttributesW` folder hiding is done from Java through JNA; the DLL does
   not duplicate it.
2. Hook `wglSwapBuffers` and draw the off-screen HUD to the front buffer after
   the swap, so a capture of the presented frame sees no HUD.

## Requirements

- Windows 10/11 x64
- Visual Studio 2022 Build Tools with the "Desktop development with C++" workload
- CMake 3.20+
- Git (MinHook is fetched at configure time)

## Build

One command, toolchain resolved for you (vswhere → MSVC, then the CMake bundled
with the IDE, then PATH):

```powershell
powershell -ExecutionPolicy Bypass -File native/build.ps1
```

It configures `-A x64`, builds Release, and copies the result to
`%APPDATA%\depends\native\ucstealth.dll` with HIDDEN+SYSTEM already set.

By hand, if you prefer:

```powershell
cd native
cmake -B build -A x64
cmake --build build --config Release
```

The result is `native/build/Release/ucstealth.dll`.

To build with the (unused) DXGI Present detour:

```powershell
powershell -ExecutionPolicy Bypass -File native/build.ps1 -Dxgi
```

or `cmake -B build -A x64 -DUC_HOOK_DXGI=ON && cmake --build build --config Release`.
MinHook is fetched by CMake from GitHub at configure time, so the first run needs
git and network access.

## Install

The client loads the DLL from the same hidden folder that holds its config:

```powershell
$dir = Join-Path $env:APPDATA 'depends\native'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Copy-Item native\build\Release\ucstealth.dll $dir\
```

Linux (Proton) path used by the injector:

```
~/.wine/drive_c/users/$USER/AppData/Roaming/.Wine/native/ucstealth.dll
```

Loading happens on first init (`StealthNative.ensureLoaded`). A missing DLL is
not an error: the client falls back to suppression whenever a capture process is
running, which is the fail-closed path.

## How the swap works

- Capture software reads the back buffer at `Present`/`SwapBuffers`.
- Two detours are installed: `opengl32!wglSwapBuffers` and, when the export is
  present, `glfwSwapBuffers` from whichever loaded module LWJGL extracted GLFW
  into. GLFW calls `wglSwapBuffers` internally, so a per-thread depth counter
  keeps the inner detour quiet and the outer one owns the single draw.
- The hook calls the original swap first, then binds the default framebuffer and
  `glDrawBuffer(GL_FRONT)` and draws the HUD texture.
- The presented frame is clean, the monitor shows the overlay.
- The next frame's swap replaces the front buffer with a fresh clean frame before
  the overlay is drawn again, so the visible result is stable.

Hooking only works while the OpenGL context is current on the render thread,
which is where `wglSwapBuffers` runs.

## Limitations

- Front-buffer drawing is not guaranteed to be visible on every driver
  (some composite the window instead of reading the front buffer). If the HUD
  does not appear while a capture is active, the layer is failing closed.
- Exclusive fullscreen bypasses this path; use windowed/borderless.
- A capture that hooks `wglSwapBuffers` inside the game process (some overlays do)
  sees the arguments, not the front buffer; only window exclusion
  (`WDA_EXCLUDEFROMCAPTURE`) covers that case.
- The DLL was not compiled in the development environment this code was written
  in (no MSVC toolchain available there). Treat the first build as bring-up:
  compile, load, check `StealthNative.lastError()` through the client log.
