//
// Created by liqi on 19-8-27.
//

#ifndef PREGELGPU_JNIPLUGIN_H
#define PREGELGPU_JNIPLUGIN_H

#include "../Graph_Algo/core/Graph.h"
#include "../Graph_Algo/algo/BellmanFord/BellmanFord.h"
#include "../Graph_Algo/srv/UtilServer.h"
#include "../Graph_Algo/srv/UtilClient.h"
#include "proberInstance/initProber.h"
#include <cstdlib>
#include <chrono>
#include <vector>
#include <map>
#include <iostream>
#include <string>
#include <fstream>

#include <jni.h>

using namespace std;

// Throw exception
jint throwNoClassDefError(JNIEnv *env, const char *message);
jint throwIllegalArgument(JNIEnv *env, const char *message);

// Convert
std::string jString2String(JNIEnv *env, jstring jStr);


#endif //PREGELGPU_JNIPLUGIN_H
