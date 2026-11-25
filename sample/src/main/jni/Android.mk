# Tilefall's native code (a game's engine, in miniature), built when local.properties has
# lyrelog.native=true: its crashes show how native frames read once the library's symbols are
# uploaded (lyrelog symbols upload sample/build).
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := tilefall
LOCAL_SRC_FILES := tilefall.cpp
LOCAL_CPPFLAGS := -O2 -g
include $(BUILD_SHARED_LIBRARY)
