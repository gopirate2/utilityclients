// swap_hook.cpp — draws the off-screen HUD to the front buffer after Present.
//
// Why after: OBS/Discord/XSplit read the frame that was handed to the desktop
// compositor, i.e. the back buffer at swap time. Drawing into the *front* buffer
// once the swap has returned puts pixels on the monitor that were never
// presented, so a capture of that present contains the clean frame.
//
// The client renders through OpenGL (LWJGL), so wglSwapBuffers is the only path
// this build uses. The DXGI detour is compiled in behind UC_HOOK_DXGI for a
// client that ever renders through D3D; it is off by default because hooking a
// swap chain that is never created is pure attack surface.

#include "swap_hook.h"

#include <windows.h>
#include <psapi.h>
#include <GL/gl.h>
#include <atomic>
#include <cwchar>
#include <unordered_map>
#include <vector>

// The Windows SDK ships GL 1.1 headers only; every token below is from a later
// core version. Defining them locally keeps the build dependency-free instead of
// pulling in a GLEW copy just for constants.
#ifndef GL_ARRAY_BUFFER
#define GL_ARRAY_BUFFER 0x8892
#endif
#ifndef GL_STATIC_DRAW
#define GL_STATIC_DRAW 0x88E4
#endif
#ifndef GL_FRAGMENT_SHADER
#define GL_FRAGMENT_SHADER 0x8B30
#endif
#ifndef GL_VERTEX_SHADER
#define GL_VERTEX_SHADER 0x8B31
#endif
#ifndef GL_COMPILE_STATUS
#define GL_COMPILE_STATUS 0x8B81
#endif
#ifndef GL_LINK_STATUS
#define GL_LINK_STATUS 0x8B82
#endif
#ifndef GL_TEXTURE0
#define GL_TEXTURE0 0x84C0
#endif
#ifndef GL_FRAMEBUFFER
#define GL_FRAMEBUFFER 0x8D40
#endif
#ifndef GL_VERTEX_ARRAY_BINDING
#define GL_VERTEX_ARRAY_BINDING 0x85B5
#endif
#ifndef GL_TEXTURE_BINDING_2D
#define GL_TEXTURE_BINDING_2D 0x8069
#endif
#ifndef GL_ACTIVE_TEXTURE
#define GL_ACTIVE_TEXTURE 0x84E0
#endif
#ifndef GL_DRAW_BUFFER
#define GL_DRAW_BUFFER 0x0C01
#endif

#ifndef GL_CURRENT_PROGRAM
#define GL_CURRENT_PROGRAM 0x8B8D
#endif
#ifndef GL_ARRAY_BUFFER_BINDING
#define GL_ARRAY_BUFFER_BINDING 0x8894
#endif
#ifndef GL_DRAW_FRAMEBUFFER
#define GL_DRAW_FRAMEBUFFER 0x8CA9
#endif
#ifndef GL_READ_FRAMEBUFFER
#define GL_READ_FRAMEBUFFER 0x8CA8
#endif
#ifndef GL_DRAW_FRAMEBUFFER_BINDING
#define GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#endif
#ifndef GL_READ_FRAMEBUFFER_BINDING
#define GL_READ_FRAMEBUFFER_BINDING 0x8CAA
#endif
#ifndef GL_BLEND_SRC_RGB
#define GL_BLEND_SRC_RGB 0x80C9
#endif
#ifndef GL_BLEND_DST_RGB
#define GL_BLEND_DST_RGB 0x80C8
#endif
#ifndef GL_BLEND_SRC_ALPHA
#define GL_BLEND_SRC_ALPHA 0x80CB
#endif
#ifndef GL_BLEND_DST_ALPHA
#define GL_BLEND_DST_ALPHA 0x80CA
#endif
#ifndef GL_BLEND_EQUATION_RGB
#define GL_BLEND_EQUATION_RGB 0x8009
#endif
#ifndef GL_BLEND_EQUATION_ALPHA
#define GL_BLEND_EQUATION_ALPHA 0x883D
#endif
#ifndef GL_FUNC_ADD
#define GL_FUNC_ADD 0x8006
#endif

typedef ptrdiff_t GLsizeiptr;
typedef char GLchar;
#include <cstdio>
#include <cstring>
#include <mutex>

#include "MinHook.h"

#ifdef UC_HOOK_DXGI
#include <d3d11.h>
#include <dxgi.h>
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#endif

#pragma comment(lib, "opengl32.lib")
#pragma comment(lib, "psapi.lib")

namespace {

// ---- state ---------------------------------------------------------------

std::atomic<bool> g_enabled{false};
std::atomic<bool> g_installed{false};
std::mutex g_installMutex;
bool g_minHookOwned = false;
std::vector<LPVOID> g_ownedHooks;

bool CreateAndEnableHook(LPVOID target, LPVOID detour, LPVOID* original) {
    if (MH_CreateHook(target, detour, original) != MH_OK) return false;
    if (MH_EnableHook(target) != MH_OK) {
        MH_RemoveHook(target);
        if (original) *original = nullptr;
        return false;
    }
    g_ownedHooks.push_back(target);
    return true;
}

void RemoveOwnedHooks() {
    for (auto target = g_ownedHooks.rbegin(); target != g_ownedHooks.rend(); ++target) {
        MH_DisableHook(*target);
        MH_RemoveHook(*target);
    }
    g_ownedHooks.clear();
}
std::atomic<int> g_texture{0};
std::atomic<int> g_width{0};
std::atomic<int> g_height{0};
char g_error[256] = "ok";
std::mutex g_errorMutex;

BOOL(WINAPI* g_originalSwap)(HDC) = nullptr;

// GLFW's own swap, hooked separately: LWJGL links glfwSwapBuffers out of
// glfw.dll and GLFW calls wglSwapBuffers internally, so both detours fire for one
// present. g_swapDepth tells the inner one to stay quiet.
void (*g_originalGlfwSwap)(void*) = nullptr;
thread_local int g_swapDepth = 0;

// ---- GL entry points -----------------------------------------------------

typedef void(APIENTRY* PFNGLBINDFRAMEBUFFER)(GLenum, GLuint);
typedef void(APIENTRY* PFNGLDRAWBUFFER)(GLenum);
typedef void(APIENTRY* PFNGLGENVERTEXARRAYS)(GLsizei, GLuint*);
typedef void(APIENTRY* PFNGLBINDVERTEXARRAY)(GLuint);
typedef void(APIENTRY* PFNGLDELETEVERTEXARRAYS)(GLsizei, const GLuint*);
typedef void(APIENTRY* PFNGLGENBUFFERS)(GLsizei, GLuint*);
typedef void(APIENTRY* PFNGLBINDBUFFER)(GLenum, GLuint);
typedef void(APIENTRY* PFNGLBUFFERDATA)(GLenum, GLsizeiptr, const void*, GLenum);
typedef void(APIENTRY* PFNGLDELETEBUFFERS)(GLsizei, const GLuint*);
typedef void(APIENTRY* PFNGLUSEPROGRAM)(GLuint);
typedef GLuint(APIENTRY* PFNGLCREATESHADER)(GLenum);
typedef void(APIENTRY* PFNGLSHADERSOURCE)(GLuint, GLsizei, const GLchar* const*, const GLint*);
typedef void(APIENTRY* PFNGLCOMPILESHADER)(GLuint);
typedef void(APIENTRY* PFNGLGETSHADERIV)(GLuint, GLenum, GLint*);
typedef void(APIENTRY* PFNGLGETSHADERINFOLOG)(GLuint, GLsizei, GLsizei*, GLchar*);
typedef GLuint(APIENTRY* PFNGLCREATEPROGRAM)(void);
typedef void(APIENTRY* PFNGLATTACHSHADER)(GLuint, GLuint);
typedef void(APIENTRY* PFNGLLINKPROGRAM)(GLuint);
typedef void(APIENTRY* PFNGLGETPROGRAMIV)(GLuint, GLenum, GLint*);
typedef void(APIENTRY* PFNGLDELETESHADER)(GLuint);
typedef void(APIENTRY* PFNGLDELETEPROGRAM)(GLuint);
typedef void(APIENTRY* PFNGLACTIVETEXTURE)(GLenum);
typedef void(APIENTRY* PFNGLVERTEXATTRIBPOINTER)(GLuint, GLint, GLenum, GLboolean, GLsizei, const void*);
typedef void(APIENTRY* PFNGLENABLEVERTEXATTRIBARRAY)(GLuint);

typedef void(APIENTRY* PFNGLBLENDFUNCSEPARATE)(GLenum, GLenum, GLenum, GLenum);
typedef void(APIENTRY* PFNGLBLENDEQUATIONSEPARATE)(GLenum, GLenum);

struct GlApi {
    PFNGLBLENDFUNCSEPARATE BlendFuncSeparate = nullptr;
    PFNGLBLENDEQUATIONSEPARATE BlendEquationSeparate = nullptr;
    PFNGLBINDFRAMEBUFFER BindFramebuffer = nullptr;
    PFNGLDRAWBUFFER DrawBuffer = nullptr;
    PFNGLGENVERTEXARRAYS GenVertexArrays = nullptr;
    PFNGLBINDVERTEXARRAY BindVertexArray = nullptr;
    PFNGLDELETEVERTEXARRAYS DeleteVertexArrays = nullptr;
    PFNGLGENBUFFERS GenBuffers = nullptr;
    PFNGLBINDBUFFER BindBuffer = nullptr;
    PFNGLBUFFERDATA BufferData = nullptr;
    PFNGLDELETEBUFFERS DeleteBuffers = nullptr;
    PFNGLUSEPROGRAM UseProgram = nullptr;
    PFNGLCREATESHADER CreateShader = nullptr;
    PFNGLSHADERSOURCE ShaderSource = nullptr;
    PFNGLCOMPILESHADER CompileShader = nullptr;
    PFNGLGETSHADERIV GetShaderiv = nullptr;
    PFNGLGETSHADERINFOLOG GetShaderInfoLog = nullptr;
    PFNGLCREATEPROGRAM CreateProgram = nullptr;
    PFNGLATTACHSHADER AttachShader = nullptr;
    PFNGLLINKPROGRAM LinkProgram = nullptr;
    PFNGLGETPROGRAMIV GetProgramiv = nullptr;
    PFNGLDELETESHADER DeleteShader = nullptr;
    PFNGLDELETEPROGRAM DeleteProgram = nullptr;
    PFNGLACTIVETEXTURE ActiveTexture = nullptr;
    PFNGLVERTEXATTRIBPOINTER VertexAttribPointer = nullptr;
    PFNGLENABLEVERTEXATTRIBARRAY EnableVertexAttribArray = nullptr;
};

HMODULE g_opengl32 = nullptr;
struct GlResources {
    GlApi api;
    GLuint program = 0, vao = 0, vbo = 0;
    bool ready = false, failed = false;
};
std::unordered_map<HGLRC, GlResources> g_resources;
std::mutex g_glMutex;
thread_local GlResources* g_current = nullptr;
#define g_gl (g_current->api)
#define g_program (g_current->program)
#define g_vao (g_current->vao)
#define g_vbo (g_current->vbo)
#define g_glReady (g_current->ready)
#define g_glFailed (g_current->failed)
BOOL(WINAPI* g_originalDeleteContext)(HGLRC) = nullptr;
HGLRC g_textureContext = nullptr;

void SetError(const char* message) {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    std::snprintf(g_error, sizeof(g_error), "%s", message ? message : "unknown");
}

template <typename T>
T Proc(const char* name) {
    PROC address = wglGetProcAddress(name);
    if (!address || address == reinterpret_cast<PROC>(1) || address == reinterpret_cast<PROC>(2)
            || address == reinterpret_cast<PROC>(3) || address == reinterpret_cast<PROC>(-1))
        address = g_opengl32 ? GetProcAddress(g_opengl32, name) : nullptr;
    return reinterpret_cast<T>(address);
}

bool ResolveGl() {
    if (g_glReady) return true;
    if (g_glFailed) return false;

    g_gl.BlendFuncSeparate = Proc<PFNGLBLENDFUNCSEPARATE>("glBlendFuncSeparate");
    g_gl.BlendEquationSeparate = Proc<PFNGLBLENDEQUATIONSEPARATE>("glBlendEquationSeparate");
    g_gl.BindFramebuffer = Proc<PFNGLBINDFRAMEBUFFER>("glBindFramebuffer");
    g_gl.DrawBuffer = Proc<PFNGLDRAWBUFFER>("glDrawBuffer");
    g_gl.GenVertexArrays = Proc<PFNGLGENVERTEXARRAYS>("glGenVertexArrays");
    g_gl.BindVertexArray = Proc<PFNGLBINDVERTEXARRAY>("glBindVertexArray");
    g_gl.DeleteVertexArrays = Proc<PFNGLDELETEVERTEXARRAYS>("glDeleteVertexArrays");
    g_gl.GenBuffers = Proc<PFNGLGENBUFFERS>("glGenBuffers");
    g_gl.BindBuffer = Proc<PFNGLBINDBUFFER>("glBindBuffer");
    g_gl.BufferData = Proc<PFNGLBUFFERDATA>("glBufferData");
    g_gl.DeleteBuffers = Proc<PFNGLDELETEBUFFERS>("glDeleteBuffers");
    g_gl.UseProgram = Proc<PFNGLUSEPROGRAM>("glUseProgram");
    g_gl.CreateShader = Proc<PFNGLCREATESHADER>("glCreateShader");
    g_gl.ShaderSource = Proc<PFNGLSHADERSOURCE>("glShaderSource");
    g_gl.CompileShader = Proc<PFNGLCOMPILESHADER>("glCompileShader");
    g_gl.GetShaderiv = Proc<PFNGLGETSHADERIV>("glGetShaderiv");
    g_gl.GetShaderInfoLog = Proc<PFNGLGETSHADERINFOLOG>("glGetShaderInfoLog");
    g_gl.CreateProgram = Proc<PFNGLCREATEPROGRAM>("glCreateProgram");
    g_gl.AttachShader = Proc<PFNGLATTACHSHADER>("glAttachShader");
    g_gl.LinkProgram = Proc<PFNGLLINKPROGRAM>("glLinkProgram");
    g_gl.GetProgramiv = Proc<PFNGLGETPROGRAMIV>("glGetProgramiv");
    g_gl.DeleteShader = Proc<PFNGLDELETESHADER>("glDeleteShader");
    g_gl.DeleteProgram = Proc<PFNGLDELETEPROGRAM>("glDeleteProgram");
    g_gl.ActiveTexture = Proc<PFNGLACTIVETEXTURE>("glActiveTexture");
    g_gl.VertexAttribPointer = Proc<PFNGLVERTEXATTRIBPOINTER>("glVertexAttribPointer");
    g_gl.EnableVertexAttribArray = Proc<PFNGLENABLEVERTEXATTRIBARRAY>("glEnableVertexAttribArray");

    if (!g_gl.CreateShader || !g_gl.CreateProgram || !g_gl.BindFramebuffer || !g_gl.DrawBuffer
        || !g_gl.GenVertexArrays || !g_gl.BindVertexArray || !g_gl.DeleteVertexArrays
        || !g_gl.GenBuffers || !g_gl.BindBuffer || !g_gl.BufferData || !g_gl.DeleteBuffers
        || !g_gl.UseProgram || !g_gl.ShaderSource || !g_gl.CompileShader || !g_gl.GetShaderiv
        || !g_gl.GetShaderInfoLog || !g_gl.AttachShader || !g_gl.LinkProgram || !g_gl.GetProgramiv
        || !g_gl.DeleteShader || !g_gl.DeleteProgram || !g_gl.ActiveTexture
        || !g_gl.VertexAttribPointer || !g_gl.EnableVertexAttribArray
        || !g_gl.BlendFuncSeparate || !g_gl.BlendEquationSeparate) {
        g_glFailed = true;
        SetError("OpenGL 3 entry points unavailable");
        return false;
    }
    g_glReady = true;
    return true;
}

GLuint Compile(GLenum type, const char* source) {
    GLuint shader = g_gl.CreateShader(type);
    if (!shader) return 0;
    g_gl.ShaderSource(shader, 1, &source, nullptr);
    g_gl.CompileShader(shader);

    GLint ok = GL_FALSE;
    g_gl.GetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        g_gl.GetShaderInfoLog(shader, sizeof(log) - 1, nullptr, log);
        SetError(log);
        g_gl.DeleteShader(shader);
        return 0;
    }
    return shader;
}

bool BuildProgram() {
    static const char* kVertex =
        "#version 330 core\n"
        "layout(location = 0) in vec2 aPos;\n"
        "layout(location = 1) in vec2 aUv;\n"
        "out vec2 vUv;\n"
        "void main() { vUv = aUv; gl_Position = vec4(aPos, 0.0, 1.0); }\n";

    static const char* kFragment =
        "#version 330 core\n"
        "uniform sampler2D uTex;\n"
        "in vec2 vUv;\n"
        "out vec4 fragColor;\n"
        "void main() { fragColor = texture(uTex, vUv); }\n";

    GLuint vertexShader = Compile(GL_VERTEX_SHADER, kVertex);
    GLuint fragmentShader = Compile(GL_FRAGMENT_SHADER, kFragment);
    if (!vertexShader || !fragmentShader) {
        if (vertexShader) g_gl.DeleteShader(vertexShader);
        if (fragmentShader) g_gl.DeleteShader(fragmentShader);
        return false;
    }

    GLuint program = g_gl.CreateProgram();
    g_gl.AttachShader(program, vertexShader);
    g_gl.AttachShader(program, fragmentShader);
    g_gl.LinkProgram(program);

    GLint linked = GL_FALSE;
    g_gl.GetProgramiv(program, GL_LINK_STATUS, &linked);
    g_gl.DeleteShader(vertexShader);
    g_gl.DeleteShader(fragmentShader);

    if (!linked) {
        SetError("overlay program failed to link");
        g_gl.DeleteProgram(program);
        return false;
    }
    g_program = program;
    return true;
}

bool BuildGeometry() {
    // Two triangles in NDC; uv matches NDC orientation so the texture is not
    // flipped relative to how it was rendered into the framebuffer.
    const float vertices[] = {
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f,
    };

    if (!g_gl.GenVertexArrays || !g_gl.BindVertexArray || !g_gl.GenBuffers
        || !g_gl.VertexAttribPointer || !g_gl.EnableVertexAttribArray) {
        return false;
    }

    g_gl.GenVertexArrays(1, &g_vao);
    g_gl.BindVertexArray(g_vao);
    g_gl.GenBuffers(1, &g_vbo);
    g_gl.BindBuffer(GL_ARRAY_BUFFER, g_vbo);
    g_gl.BufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);

    // location 0 = position, location 1 = uv; 4 floats per vertex.
    g_gl.EnableVertexAttribArray(0);
    g_gl.VertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), nullptr);
    g_gl.EnableVertexAttribArray(1);
    g_gl.VertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                             reinterpret_cast<const void*>(2 * sizeof(float)));
    return true;
}

void ReleaseGlObjects() {
    if (g_program && g_gl.DeleteProgram) {
        g_gl.DeleteProgram(g_program);
    }
    if (g_vao && g_gl.DeleteVertexArrays) {
        g_gl.DeleteVertexArrays(1, &g_vao);
    }
    if (g_vbo && g_gl.DeleteBuffers) {
        g_gl.DeleteBuffers(1, &g_vbo);
    }
    g_program = 0;
    g_vao = 0;
    g_vbo = 0;
}

struct GlState {
    GLint program, vao, arrayBuffer, active, texture0, drawFbo, readFbo, defaultDraw;
    GLint viewport[4], srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha;
    GLboolean blend, depth, cull, scissor;
    GlState() {
        glGetIntegerv(GL_CURRENT_PROGRAM, &program);
        glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer);
        glGetIntegerv(GL_ACTIVE_TEXTURE, &active);
        g_gl.ActiveTexture(GL_TEXTURE0);
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &texture0);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFbo);
        glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFbo);
        g_gl.BindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glGetIntegerv(GL_DRAW_BUFFER, &defaultDraw);
        glGetIntegerv(GL_VIEWPORT, viewport);
        glGetIntegerv(GL_BLEND_SRC_RGB, &srcRgb);
        glGetIntegerv(GL_BLEND_DST_RGB, &dstRgb);
        glGetIntegerv(GL_BLEND_SRC_ALPHA, &srcAlpha);
        glGetIntegerv(GL_BLEND_DST_ALPHA, &dstAlpha);
        glGetIntegerv(GL_BLEND_EQUATION_RGB, &equationRgb);
        glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &equationAlpha);
        blend = glIsEnabled(GL_BLEND);
        depth = glIsEnabled(GL_DEPTH_TEST);
        cull = glIsEnabled(GL_CULL_FACE);
        scissor = glIsEnabled(GL_SCISSOR_TEST);
    }
    ~GlState() {
        g_gl.BindVertexArray(static_cast<GLuint>(vao));
        g_gl.BindBuffer(GL_ARRAY_BUFFER, static_cast<GLuint>(arrayBuffer));
        g_gl.UseProgram(static_cast<GLuint>(program));
        g_gl.ActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(texture0));
        g_gl.ActiveTexture(static_cast<GLenum>(active));
        g_gl.BlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        g_gl.BlendEquationSeparate(equationRgb, equationAlpha);
        if (blend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (depth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (cull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
        if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        g_gl.BindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        g_gl.DrawBuffer(static_cast<GLenum>(defaultDraw));
        g_gl.BindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(drawFbo));
        g_gl.BindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(readFbo));
    }
};

void DrawOverlay(HDC deviceContext) {
    if (!g_enabled.load(std::memory_order_relaxed)) return;
    HGLRC context = wglGetCurrentContext();
    if (!context) return;
    std::lock_guard<std::mutex> lock(g_glMutex);
    if (context != g_textureContext) return;
    const int texture = g_texture.load(std::memory_order_relaxed);
    const int width = g_width.load(std::memory_order_relaxed);
    const int height = g_height.load(std::memory_order_relaxed);
    if (texture <= 0 || width <= 0 || height <= 0) return;
    g_current = &g_resources[context];
    if (!ResolveGl()) return;
    // Save before geometry creation, which changes both VAO and buffer bindings.
    GlState saved;
    if (!g_program && !BuildProgram()) return;
    if (!g_vao && !BuildGeometry()) return;
    g_gl.DrawBuffer(GL_FRONT);
    glViewport(0, 0, width, height);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glEnable(GL_BLEND);
    g_gl.BlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    g_gl.BlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
    g_gl.UseProgram(g_program);
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(texture));
    g_gl.BindVertexArray(g_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    (void)deviceContext;
}

BOOL WINAPI DetourDeleteContext(HGLRC context) {
    {
        std::lock_guard<std::mutex> lock(g_glMutex);
        auto found = g_resources.find(context);
        if (found != g_resources.end()) {
            // Only issue deletion calls while the owning context is current.
            if (wglGetCurrentContext() == context) {
                g_current = &found->second;
                ReleaseGlObjects();
            }
            g_resources.erase(found);
        }
        if (g_textureContext == context) { g_textureContext = nullptr; g_texture.store(0); }
        g_current = nullptr;
    }
    return g_originalDeleteContext ? g_originalDeleteContext(context) : FALSE;
}

void DrawOverlayGuarded() {
    __try {
        DrawOverlay(nullptr);
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        // A driver mismatch in the overlay must never take the frame down.
        SetError("exception in overlay draw");
    }
}

BOOL WINAPI DetourSwapBuffers(HDC deviceContext) {
    BOOL result = g_originalSwap ? g_originalSwap(deviceContext) : FALSE;
    // Inside a glfwSwapBuffers call: the outer detour owns the single draw.
    if (g_swapDepth == 0) DrawOverlayGuarded();
    return result;
}

void DetourGlfwSwapBuffers(void* window) {
    ++g_swapDepth;
    if (g_originalGlfwSwap) g_originalGlfwSwap(window);
    --g_swapDepth;
    DrawOverlayGuarded();
}

#ifdef UC_HOOK_DXGI
typedef HRESULT(WINAPI* PFN_PRESENT)(IDXGISwapChain*, UINT, UINT);
PFN_PRESENT g_originalPresent = nullptr;

HRESULT WINAPI DetourPresent(IDXGISwapChain* chain, UINT syncInterval, UINT flags) {
    HRESULT result = g_originalPresent ? g_originalPresent(chain, syncInterval, flags) : E_FAIL;
    // D3D presents flip the back buffer; there is no front-buffer equivalent to
    // draw into. A D3D client needs an overlay swap chain instead, which is why
    // this path is not the one this client uses.
    return result;
}

bool InstallDxgi() {
    // Present lives on IDXGISwapChain's vtable and the game's own chain does not
    // exist until it renders, so the address comes from a throwaway chain on a
    // hidden 4x4 window. The vtable is per-interface, not per-instance, which is
    // the whole point: one probe chain addresses every chain in the process.
    WNDCLASSEXW windowClass{};
    windowClass.cbSize = sizeof(windowClass);
    windowClass.lpfnWndProc = DefWindowProcW;
    windowClass.hInstance = GetModuleHandleW(nullptr);
    windowClass.lpszClassName = L"uc_dxgi_probe";
    if (!RegisterClassExW(&windowClass)
        && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
        SetError("RegisterClassExW failed for the DXGI probe");
        return false;
    }

    HWND window = CreateWindowExW(0, windowClass.lpszClassName, L"", WS_OVERLAPPEDWINDOW,
                                  0, 0, 4, 4, nullptr, nullptr,
                                  windowClass.hInstance, nullptr);
    if (!window) {
        UnregisterClassW(windowClass.lpszClassName, windowClass.hInstance);
        SetError("CreateWindowExW failed for the DXGI probe");
        return false;
    }

    DXGI_SWAP_CHAIN_DESC description{};
    description.BufferCount = 1;
    description.BufferDesc.Width = 4;
    description.BufferDesc.Height = 4;
    description.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    description.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    description.OutputWindow = window;
    description.SampleDesc.Count = 1;
    description.Windowed = TRUE;

    ID3D11Device* device = nullptr;
    ID3D11DeviceContext* context = nullptr;
    IDXGISwapChain* chain = nullptr;
    D3D_FEATURE_LEVEL level{};

    HRESULT created = D3D11CreateDeviceAndSwapChain(
            nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION,
            &description, &chain, &device, &level, &context);

    bool hooked = false;
    if (SUCCEEDED(created) && chain) {
        auto** vtable = *reinterpret_cast<void***>(chain);
        if (vtable && vtable[8]) {
            hooked = CreateAndEnableHook(vtable[8], reinterpret_cast<LPVOID>(&DetourPresent),
                                         reinterpret_cast<LPVOID*>(&g_originalPresent));
        } else {
            SetError("IDXGISwapChain::Present slot missing");
        }
        chain->Release();
    } else {
        SetError("D3D11CreateDeviceAndSwapChain failed");
    }

    if (context) context->Release();
    if (device) device->Release();
    DestroyWindow(window);
    UnregisterClassW(windowClass.lpszClassName, windowClass.hInstance);
    return hooked;
}
#endif  // UC_HOOK_DXGI

// GLFW is loaded by LWJGL from an extracted native, so the module name is not
// guaranteed. Match on the file name of every loaded module instead.
HMODULE FindGlfwModule() {
    HMODULE modules[1024];
    DWORD needed = 0;
    if (!EnumProcessModules(GetCurrentProcess(), modules, sizeof(modules), &needed)) {
        return GetModuleHandleW(L"glfw.dll");
    }
    const DWORD count = needed / sizeof(HMODULE);
    for (DWORD i = 0; i < count && i < 1024; i++) {
        wchar_t path[MAX_PATH] = {0};
        if (!GetModuleFileNameW(modules[i], path, MAX_PATH)) continue;
        const wchar_t* name = wcsrchr(path, L'\\');
        name = name ? name + 1 : path;
        if (_wcsicmp(name, L"glfw.dll") == 0) return modules[i];
    }
    return nullptr;
}

}  // namespace

namespace SwapHook {

bool Install() {
    std::lock_guard<std::mutex> installLock(g_installMutex);
    if (g_installed.load()) return true;

    MH_STATUS initialized = MH_Initialize();
    if (initialized != MH_OK && initialized != MH_ERROR_ALREADY_INITIALIZED) {
        SetError("MH_Initialize failed");
        return false;
    }

    g_minHookOwned = initialized == MH_OK;
    auto rollback = []() {
        RemoveOwnedHooks();
        if (g_minHookOwned) MH_Uninitialize();
        g_minHookOwned = false;
        g_originalSwap = nullptr;
    };

    g_opengl32 = GetModuleHandleW(L"opengl32.dll");
    if (!g_opengl32) {
        SetError("opengl32.dll is not loaded");
        rollback();
        return false;
    }

    auto swap = GetProcAddress(g_opengl32, "wglSwapBuffers");
    if (!swap) {
        SetError("wglSwapBuffers not found");
        rollback();
        return false;
    }

    if (!CreateAndEnableHook(reinterpret_cast<LPVOID>(swap), reinterpret_cast<LPVOID>(&DetourSwapBuffers),
                             reinterpret_cast<LPVOID*>(&g_originalSwap))) {
        SetError("Could not create and enable the wglSwapBuffers hook");
        rollback();
        return false;
    }

    auto deleteContext = GetProcAddress(g_opengl32, "wglDeleteContext");
    if (deleteContext) CreateAndEnableHook(reinterpret_cast<LPVOID>(deleteContext), reinterpret_cast<LPVOID>(&DetourDeleteContext),
                                         reinterpret_cast<LPVOID*>(&g_originalDeleteContext));

    // Optional: the GLFW entry point. Missing here is not a failure — GLFW is
    // built by LWJGL and the export name is not part of any contract, so the wgl
    // hook above remains the path that must work.
    HMODULE glfw = FindGlfwModule();
    if (glfw) {
        auto glfwSwap = GetProcAddress(glfw, "glfwSwapBuffers");
        if (glfwSwap
            && CreateAndEnableHook(reinterpret_cast<LPVOID>(glfwSwap), reinterpret_cast<LPVOID>(&DetourGlfwSwapBuffers),
                                   reinterpret_cast<LPVOID*>(&g_originalGlfwSwap))) {}
    }

#ifdef UC_HOOK_DXGI
    InstallDxgi();
#endif

    g_installed.store(true);
    SetError("ok");
    return true;
}

void Uninstall() {
    std::lock_guard<std::mutex> installLock(g_installMutex);
    if (!g_installed.exchange(false)) return;
    g_enabled.store(false);
    RemoveOwnedHooks();
    if (g_minHookOwned) MH_Uninitialize();
    g_minHookOwned = false;
    std::lock_guard<std::mutex> lock(g_glMutex);
    auto found = g_resources.find(wglGetCurrentContext());
    if (found != g_resources.end()) { g_current = &found->second; ReleaseGlObjects(); }
    g_resources.clear();
    g_current = nullptr;
    g_textureContext = nullptr;
    g_texture.store(0);
}

void SetOverlay(int texture, int width, int height) {
    std::lock_guard<std::mutex> lock(g_glMutex);
    g_textureContext = wglGetCurrentContext();
    g_texture.store(texture, std::memory_order_relaxed);
    g_width.store(width, std::memory_order_relaxed);
    g_height.store(height, std::memory_order_relaxed);
}

void SetEnabled(bool enabled) {
    g_enabled.store(enabled, std::memory_order_relaxed);
}

bool Installed() {
    return g_installed.load();
}

const char* LastError() {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    return g_error;
}

}  // namespace SwapHook
