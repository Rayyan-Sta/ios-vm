#pragma once
#include <android/log.h>
#define IOSVM_TAG "iosvm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  IOSVM_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  IOSVM_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, IOSVM_TAG, __VA_ARGS__)
