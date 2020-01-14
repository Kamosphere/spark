cmake_minimum_required(VERSION 3.9)
project(prober)

set(CMAKE_CXX_STANDARD 14)

set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)

#use $ENV{JAVA_HOME} to include the jni header, JAVA_HOME need to be configurated
INCLUDE_DIRECTORIES($ENV{JAVA_HOME}/include)
if(WIN32)
    INCLUDE_DIRECTORIES($ENV{JAVA_HOME}/include/win32)
ELSEIF(APPLE)
    INCLUDE_DIRECTORIES($ENV{JAVA_HOME}/include/darwin)
ELSE()
    INCLUDE_DIRECTORIES($ENV{JAVA_HOME}/include/linux)
endif()

add_subdirectory(proberInstance)

add_library(JNIPlugin
        JNIPlugin.cpp
        JNIPlugin.h)
