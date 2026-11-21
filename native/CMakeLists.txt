cmake_minimum_required(VERSION 3.24)
project(ucstealth LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
    minhook
    URL https://github.com/TsudaKageyu/minhook/archive/refs/tags/v1.3.4.zip
    URL_HASH SHA256=172708123daa0c98d20d3a980b16a50be14af243dc95dee6f79c24193ad010e4
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(minhook)

add_library(ucstealth SHARED
    dllmain.cpp
    swap_hook.cpp
)

target_include_directories(ucstealth PRIVATE ${minhook_SOURCE_DIR}/include)
target_link_libraries(ucstealth PRIVATE minhook opengl32 dxgi d3d11)

# The client renders through OpenGL. Turn the DXGI Present detour on only for a
# D3D-backed build; see swap_hook.cpp for why it is not the default.
option(UC_HOOK_DXGI "Install the IDXGISwapChain::Present detour" OFF)
if(UC_HOOK_DXGI)
    target_compile_definitions(ucstealth PRIVATE UC_HOOK_DXGI=1)
endif()

set_target_properties(ucstealth PROPERTIES OUTPUT_NAME "ucstealth")
