cmake_minimum_required(VERSION 3.15)
project(igatool)

set(CMAKE_CXX_STANDARD 14)

add_executable(igatool igatool.cpp)
target_compile_options(igatool PRIVATE -Wall -Wextra -pedantic -Werror)
