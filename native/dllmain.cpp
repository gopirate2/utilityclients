// dllmain.cpp — entry point and the exports JNA resolves by name.
//
// Loaded into the game JVM through Native.load(...), not injected into another
// process. DllMain only captures the module handle: hook installation happens in
// uc_install(), on the calling thread, because DllMain runs under the loader
// lock and must not touch the graphics stack.

#include <windows.h>

#include "swap_hook.h"

namespace {
HMODULE g_module = nullptr;
}

BOOL APIENTRY DllMain(HMODULE module, DWORD reason, LPVOID reserved) {
    (void)reserved;
    switch (reason) {
        case DLL_PROCESS_ATTACH:
            g_module = module;
            DisableThreadLibraryCalls(module);
            break;
        case DLL_PROCESS_DETACH:
            // Explicit uc_uninstall performs teardown outside the loader lock.
            break;
        default:
            break;
    }
    return TRUE;
}

extern "C" {

__declspec(dllexport) int uc_install(void) {
    return SwapHook::Install() ? 0 : 1;
}

__declspec(dllexport) void uc_uninstall(void) {
    SwapHook::Uninstall();
}

__declspec(dllexport) void uc_set_overlay(int texture, int width, int height) {
    SwapHook::SetOverlay(texture, width, height);
}

__declspec(dllexport) void uc_set_enabled(int enabled) {
    SwapHook::SetEnabled(enabled != 0);
}

__declspec(dllexport) int uc_is_installed(void) {
    return SwapHook::Installed() ? 1 : 0;
}

__declspec(dllexport) const char* uc_last_error(void) {
    return SwapHook::LastError();
}

}  // extern "C"
