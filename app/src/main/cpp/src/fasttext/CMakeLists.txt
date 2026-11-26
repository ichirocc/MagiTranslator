# Grab all fastText C++ source files
file(GLOB FASTTEXT_SRC "libs/fastText/src/*.cc")

# Exclude main.cc (contains the CLI tool's main() function, which will cause a build error)
list(REMOVE_ITEM FASTTEXT_SRC "${CMAKE_CURRENT_SOURCE_DIR}/libs/fastText/src/main.cc")

# fastText's own sources are vendored: their warnings are not ours (the wrapper keeps its warnings)
set_source_files_properties(${FASTTEXT_SRC} PROPERTIES COMPILE_OPTIONS "-w")

# Create your shared library, compiling your JNI wrapper AND the fastText source
add_library(
        fasttext-lib
        SHARED
        FastTextWrapper.cpp
        ${FASTTEXT_SRC}
)

# Link standard Android logging
target_link_libraries(fasttext-lib
        android
        log)