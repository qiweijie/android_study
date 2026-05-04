cmake_minimum_required(VERSION 3.18.1)

project("studydemo")

add_library(studydemo SHARED studylib.cpp native-lib.cpp)

target_link_libraries(studydemo log)