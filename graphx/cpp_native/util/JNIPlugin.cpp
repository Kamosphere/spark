//
// Created by liqi on 19-8-27.
//

#include "JNIPlugin.h"

using namespace std;
// throw error to JVM

jint throwNoClassDefError(JNIEnv *env, const char *message)
{
    jclass exClass;
    const char *className = "java/lang/NoClassDefFoundError";

    exClass = env->FindClass(className);
    if (exClass == nullptr) {
        return throwNoClassDefError( env, className );
    }

    return env->ThrowNew( exClass, message );
}

// throw error to JVM

jint throwIllegalArgument( JNIEnv *env, const char *message )
{
    jclass exClass;
    const char *className = "java/lang/IllegalArgumentException" ;

    exClass = env->FindClass( className );
    if ( exClass == nullptr ) {
        return throwNoClassDefError( env, className );
    }

    return env->ThrowNew( exClass, message );
}



std::string jString2String(JNIEnv *env, jstring jStr)
{
    if (!jStr)
        return "";

    jclass stringClass = env->GetObjectClass(jStr);
    jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    auto stringJBytes = (jbyteArray) env->CallObjectMethod(jStr, getBytes, env->NewStringUTF("UTF-8"));

    auto length = (size_t) env->GetArrayLength(stringJBytes);
    jbyte* pBytes = env->GetByteArrayElements(stringJBytes, nullptr);

    std::string ret = std::string((char *)pBytes, length);
    env->ReleaseByteArrayElements(stringJBytes, pBytes, JNI_ABORT);

    env->DeleteLocalRef(stringJBytes);
    env->DeleteLocalRef(stringClass);
    return ret;
}
