#pragma once

namespace SwapHook {

// Installs the wglSwapBuffers detour (and the DXGI Present detour when built
// with UC_HOOK_DXGI). Idempotent. Returns false and sets LastError on failure.
bool Install();

// Removes every detour and releases GL objects owned by the hook.
void Uninstall();

// Registers the off-screen HUD texture. Called by Java every frame; cheap.
void SetOverlay(int texture, int width, int height);

// Gates the post-swap draw without removing the hook.
void SetEnabled(bool enabled);

bool Installed();

// Static buffer, valid until the next failing call.
const char* LastError();

}  // namespace SwapHook
