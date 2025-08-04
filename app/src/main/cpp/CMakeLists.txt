cmake_minimum_required(VERSION 3.22.1)

project("whisper-jni")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Add compiler flags for optimization
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG")

# Find required packages
find_library(log-lib log)
find_library(android-lib android)

# Add whisper.cpp as a subdirectory (you'll need to add whisper.cpp source)
# For now, we'll assume whisper.cpp is added as a git submodule or downloaded
set(WHISPER_ROOT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# Check if whisper.cpp exists
if(EXISTS ${WHISPER_ROOT_DIR})
    message(STATUS "Found whisper.cpp at ${WHISPER_ROOT_DIR}")
    
    # Add whisper.cpp subdirectory
    add_subdirectory(${WHISPER_ROOT_DIR} whisper_build)
    
    # Create our JNI library with a different name to avoid conflicts
    add_library(whisper-jni SHARED whisper_jni.cpp)
    
    # Link libraries
    target_link_libraries(whisper-jni
        whisper  # This should be the library built by whisper.cpp
        ${log-lib}
        ${android-lib}
    )
    
    # Include whisper headers
    target_include_directories(whisper-jni PRIVATE ${WHISPER_ROOT_DIR})
    
else()
    message(WARNING "whisper.cpp not found at ${WHISPER_ROOT_DIR}")
    message(WARNING "Please add whisper.cpp as a git submodule or download it manually")
    
    # Create a dummy library that will fail at runtime
    add_library(whisper-jni SHARED whisper_jni.cpp)
    target_compile_definitions(whisper-jni PRIVATE NO_WHISPER)
    target_link_libraries(whisper-jni ${log-lib} ${android-lib})
endif()
