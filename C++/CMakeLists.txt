cmake_minimum_required(VERSION 3.21)
project(BlackHoleSim CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(glfw3 CONFIG REQUIRED)
find_package(glm CONFIG REQUIRED)
find_package(Vulkan REQUIRED COMPONENTS glslc)

# 3D black hole, Vulkan 1.3. The CMake target is BlackHoleVK; the executable file is named after the project and language.
add_executable(BlackHoleVK black_hole_vk.cpp)
target_link_libraries(BlackHoleVK PRIVATE glfw glm::glm Vulkan::Vulkan)
target_include_directories(BlackHoleVK PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(BlackHoleVK PROPERTIES OUTPUT_NAME black_hole_cpp)

# GLSL -> SPIR-V at build time; the .spv files are copied next to the executable (it loads them from its working directory)
set(VK_SHADERS geodesic_vk.comp grid_vk.vert grid_vk.frag quad_vk.vert quad_vk.frag)
set(VK_SPV "")
foreach(src ${VK_SHADERS})
    set(spv ${CMAKE_BINARY_DIR}/shaders/${src}.spv)
    add_custom_command(OUTPUT ${spv}
        COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_BINARY_DIR}/shaders
        COMMAND ${Vulkan_GLSLC_EXECUTABLE} --target-env=vulkan1.3 ${CMAKE_CURRENT_SOURCE_DIR}/${src} -o ${spv}
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/${src})
    list(APPEND VK_SPV ${spv})
endforeach()
add_custom_target(BlackHoleVKShaders DEPENDS ${VK_SPV})
add_dependencies(BlackHoleVK BlackHoleVKShaders)
foreach(spv ${VK_SPV})
    add_custom_command(TARGET BlackHoleVK POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different ${spv} $<TARGET_FILE_DIR:BlackHoleVK>)
endforeach()
