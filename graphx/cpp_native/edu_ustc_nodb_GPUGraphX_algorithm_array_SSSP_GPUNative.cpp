#include <cstring>
#include "edu_ustc_nodb_GPUGraphX_algorithm_array_SSSP_GPUNative.h"

#include "util/JNIPlugin.h"

using namespace std;

// Init the edge and markID

JNIEXPORT jboolean JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_array_SSSP_GPUNative_nativeEnvEdgeInit
(JNIEnv * env, jobject superClass,
        jlongArray jFilteredVertex,
        jlong vertexSum, jlongArray jEdgeSrc, jlongArray jEdgeDst, jdoubleArray jEdgeAttr,
        jobject markId, jint pid){

    jclass c_Long = env->FindClass("java/lang/Long");
    jmethodID longValue = env->GetMethodID(c_Long, "longValue", "()J");

    jclass c_ArrayList = env->FindClass("java/util/ArrayList");
    jmethodID id_ArrayList_size = env->GetMethodID(c_ArrayList, "size", "()I");
    jmethodID id_ArrayList_get = env->GetMethodID(c_ArrayList, "get", "(I)Ljava/lang/Object;");

    //---------Entity---------

    int lenMarkID = env->CallIntMethod(markId, id_ArrayList_size);
    int lenEdge = env->GetArrayLength(jEdgeSrc);
    int lenFiltered = env->GetArrayLength(jFilteredVertex);

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);

    //Init the Graph with blank vertices

    vector<Vertex> vertices = vector<Vertex>();

    double *vValues = new double [vertexAllSum * lenMarkID];
    memset(vValues, INT32_MAX, sizeof(double) * vertexAllSum * lenMarkID);

    bool* filteredV = new bool [vertexAllSum]();

    int* timestamp = new int [vertexAllSum];
    memset(timestamp, -1, sizeof(int) * vertexAllSum);

    vector<Edge> edges = vector<Edge>();

    for(int i = 0; i < vertexAllSum; i++){
        vertices.emplace_back(Vertex(i, false, INVALID_INITV_INDEX));
    }

    // fill markID, which stored the landmark

    int *initVSet = new int [lenMarkID];

    for(int i = 0; i < lenMarkID; i++)
    {
        jobject start = env->CallObjectMethod(markId, id_ArrayList_get, i);
        jlong jMarkIDUnit = env->CallLongMethod(start, longValue);
        vertices.at(jMarkIDUnit).initVIndex = i;
        initVSet[i] = jMarkIDUnit;

        env->DeleteLocalRef(start);
    }

    // Init the Graph with existed edges
    jboolean isCopy = false;
    long* FilteredVertexTemp = env->GetLongArrayElements(jFilteredVertex, &isCopy);

    long* EdgeSrcTemp = env->GetLongArrayElements(jEdgeSrc, &isCopy);
    long* EdgeDstTemp = env->GetLongArrayElements(jEdgeDst, &isCopy);
    double * EdgeAttrTemp = env->GetDoubleArrayElements(jEdgeAttr, &isCopy);

    for(int i = 0; i < lenFiltered; i++)
    {
        filteredV[FilteredVertexTemp[i]] = true;
    }

    for (int i = 0; i < lenEdge; i++)
    {
        int jSrcId_get = EdgeSrcTemp[i];
        int jDstId_get = EdgeDstTemp[i];
        double jAttr_get = EdgeAttrTemp[i];

        edges.emplace_back(Edge(jSrcId_get, jDstId_get, jAttr_get));
    }

    env->ReleaseLongArrayElements(jEdgeSrc, EdgeSrcTemp, 0);
    env->ReleaseLongArrayElements(jEdgeDst, EdgeDstTemp, 0);
    env->ReleaseDoubleArrayElements(jEdgeAttr, EdgeAttrTemp, 0);

    initProber detector = initProber(partitionID);
    bool status = detector.run();
    if(! status)
    {
        throwIllegalArgument(env, "Cannot detect existing server");
        return false;
    }

    UtilClient<double, double> execute = UtilClient<double,double>(vertexAllSum, lenEdge, lenMarkID, partitionID);

    int chk = 0;

    chk = execute.connect();
    if (chk == -1)
    {
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
        return false;
    }

    chk = execute.transfer(vValues, &vertices[0], &edges[0], initVSet, filteredV, timestamp);

    if(chk == -1)
    {
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
        return false;
    }

    execute.disconnect();

    return true;
}


JNIEXPORT jint JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_array_SSSP_GPUNative_nativeStepMsgExecute
(JNIEnv * env, jobject superClass,
        jlong vertexSum, jlongArray jVertexId, jbooleanArray jVertexActive, jdoubleArray jVertexAttr,
        jint vertexCount, jint edgeCount, jint markIdLen, jint pid, jlongArray returnId, jdoubleArray returnAttr){

    //---------Debug tools---------
    // jclass n_sztool = env->FindClass("edu/ustc/nodb/matrix/SizesTool");
    // jmethodID id_getSize = env->GetStaticMethodID(n_sztool, "getObjectSize", "(Ljava/lang/Object;)J");
    //-----------------------------

    //---------Time evaluating---------
    auto startTimeA = std::chrono::high_resolution_clock::now();


    string fileNameOutputEdgeLog = "testLogCPlusBreakDownPid" + to_string(pid)
                                   + "Time" + to_string(startTimeA.time_since_epoch().count()) + ".txt";
    string pathFile = "/usr/local/ssspexample/outputlog/";
    std::ofstream Tout(pathFile + fileNameOutputEdgeLog, fstream::out | fstream::app);

    //---------Time evaluating---------

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);
    int lenMarkID = static_cast<int>(markIdLen);
    int lenEdge = static_cast<int>(edgeCount);
    int lenVertex = static_cast<int>(vertexCount);

    UtilClient<double, double> execute = UtilClient<double, double>(vertexAllSum, lenEdge, lenMarkID, partitionID);

    int chk = 0;

    chk = execute.connect();
    if (chk == -1)
    {
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

    // Init the vertices

    vector<Vertex> vertices = vector<Vertex>();
    for(int i = 0; i < vertexAllSum; i++){
        vertices.emplace_back(Vertex(i, false, INVALID_INITV_INDEX));
    }

    double *vValues = new double [vertexAllSum * lenMarkID];
    memset(vValues, INT32_MAX, sizeof(double) * vertexAllSum * lenMarkID);

    for(jint i = 0; i < lenMarkID; i++)
    {
        vertices.at(execute.initVSet[i]).initVIndex = i;
        vertices.at(execute.initVSet[i]).vertexID = execute.initVSet[i];
    }

    jboolean isCopy = false;
    // fill vertices attributes
    long* VertexIDTemp = env->GetLongArrayElements(jVertexId, &isCopy);
    jboolean * VertexActiveTemp = env->GetBooleanArrayElements(jVertexActive, &isCopy);
    double* VertexAttrTemp = env->GetDoubleArrayElements(jVertexAttr, &isCopy);

    int avCount = 0;
    std::vector<int> avSet;
    avSet.reserve(vertexAllSum);
    avSet.assign(vertexAllSum, 0);

    for (int i = 0; i < lenVertex; i++)
    {
        // jlong sig = env->CallStaticLongMethod(n_sztool, id_getSize, vertexLL);

        long jVertexId_get = VertexIDTemp[i];
        bool jVertexActive_get = VertexActiveTemp[i];

        for(int j = 0; j < lenMarkID; j++)
        {
            double jDis = VertexAttrTemp[i * lenMarkID + j];
            int index = vertices.at(execute.initVSet[j]).initVIndex;
            if(index != INVALID_INITV_INDEX)
                vValues[jVertexId_get * lenMarkID + index] = jDis;
        }

        vertices.at(jVertexId_get).isActive = jVertexActive_get;

        if (jVertexActive_get)
        {
            avSet.at(avCount) = jVertexId_get;
            avCount++;
        }
    }

    env->ReleaseLongArrayElements(jVertexId, VertexIDTemp, 0);
    env->ReleaseBooleanArrayElements(jVertexActive, VertexActiveTemp, 0);
    env->ReleaseDoubleArrayElements(jVertexAttr, VertexAttrTemp, 0);

/*
    // test for multithreading environment

    std::string outputT = std::string();

    for(int i = 0;i < vertexAllSum; i++){
        outputT += to_string(i) + ": {";
        for(int j = 0;j < lenMarkID; j++){
            outputT += " [" + to_string(execute.initVSet[j])+": "+to_string(execute.mValues[i * lenMarkID + j]) + "] ";
        }
        outputT += "}";
    }

    cout<<outputT<<endl;

    outputT.clear();
    // test end
*/

    chk = execute.update(vValues, &vertices[0], &avSet[0], avCount);

    if(chk == -1)
    {
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

    //---------Time evaluating---------
    std::chrono::nanoseconds durationA = std::chrono::high_resolution_clock::now() - startTimeA;
    auto startTime = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    // execute sssp using GPU in server-client mode

    execute.request();

    //---------Time evaluating---------
    std::chrono::nanoseconds duration = std::chrono::high_resolution_clock::now() - startTime;

    auto startTimeB = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    vector<long> cPlusReturnId = vector<long>();
    vector<double> cPlusReturnAttr = vector<double>();

    bool allGained = true;
    for (int i = 0; i < vertexAllSum; i++)
    {
        if (execute.vSet[i].isActive)
        {
            // copy msg data that activated
            cPlusReturnId.emplace_back(i);
            for (int j = 0; j < lenMarkID; j++)
            {
                cPlusReturnAttr.emplace_back(execute.mValues[i * lenMarkID + j]);
            }
            // detect if the vertex is filtered
            if(! execute.filteredV[i])
            {
                allGained = false;
            }
        }
    }

    env->SetLongArrayRegion(returnId, 0, cPlusReturnId.size(), &cPlusReturnId[0]);
    env->SetDoubleArrayRegion(returnAttr, 0, cPlusReturnAttr.size(), &cPlusReturnAttr[0]);

    execute.disconnect();

    //---------Time evaluating---------
    std::chrono::nanoseconds durationB = std::chrono::high_resolution_clock::now() - startTimeB;

    std::string output = std::string();
    output += "Time of partition " + to_string(pid) + " in c++: " + to_string(durationA.count()) + " "
              + to_string(duration.count()) + " " + to_string(durationB.count());

    /*
    Tout<<output<<endl;

    Tout.close();
     */
    //---------Time evaluating---------

    if(allGained) return static_cast<int>(0 - cPlusReturnId.size());
    else return static_cast<int>(cPlusReturnId.size());
}

//------------------------
// NEW Skipping method
//------------------------

JNIEXPORT jint JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_array_SSSP_GPUNative_nativeStepVertexInput
(JNIEnv * env, jobject superClass,
        jlong vertexSum, jlongArray jVertexId, jbooleanArray jVertexActive, jdoubleArray jVertexAttr,
        jint vertexCount, jint edgeCount, jint markIdLen, jint pid){

    //---------Time evaluating---------
    auto startTimeA = std::chrono::high_resolution_clock::now();


    string fileNameOutputEdgeLog = "testLogCPlusBreakDownPid" + to_string(pid)
                                   + "Time" + to_string(startTimeA.time_since_epoch().count()) + ".txt";
    string pathFile = "/usr/local/ssspexample/outputlog/";
    std::ofstream Tout(pathFile + fileNameOutputEdgeLog, fstream::out | fstream::app);

    //---------Time evaluating---------

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);
    int lenMarkID = static_cast<int>(markIdLen);
    int lenEdge = static_cast<int>(edgeCount);
    int lenVertex = static_cast<int>(vertexCount);

    UtilClient<double, double> execute = UtilClient<double, double>(vertexAllSum, lenEdge, lenMarkID, partitionID);

    int chk = 0;

    chk = execute.connect();
    if (chk == -1){
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

    // Init the vertices

    vector<Vertex> vertices = vector<Vertex>();
    for(int i = 0; i < vertexAllSum; i++){
        vertices.emplace_back(Vertex(i, false, INVALID_INITV_INDEX));
    }

    double *vValues = new double [vertexAllSum * lenMarkID];
    memset(vValues, INT32_MAX, sizeof(double) * vertexAllSum * lenMarkID);

    for(jint i = 0; i < lenMarkID; i++)
    {
        vertices.at(execute.initVSet[i]).initVIndex = i;
        vertices.at(execute.initVSet[i]).vertexID = execute.initVSet[i];
    }

    jboolean isCopy = false;
    // fill vertices attributes
    long* VertexIDTemp = env->GetLongArrayElements(jVertexId, &isCopy);
    jboolean * VertexActiveTemp = env->GetBooleanArrayElements(jVertexActive, &isCopy);
    double* VertexAttrTemp = env->GetDoubleArrayElements(jVertexAttr, &isCopy);

    int avCount = 0;
    std::vector<int> avSet;
    avSet.reserve(vertexAllSum);
    avSet.assign(vertexAllSum, 0);

    for (int i = 0; i < lenVertex; i++)
    {
        // jlong sig = env->CallStaticLongMethod(n_sztool, id_getSize, vertexLL);

        long jVertexId_get = VertexIDTemp[i];
        bool jVertexActive_get = VertexActiveTemp[i];

        for(int j = 0; j < lenMarkID; j++)
        {
            double jDis = VertexAttrTemp[i * lenMarkID + j];
            int index = vertices.at(execute.initVSet[j]).initVIndex;
            if(index != INVALID_INITV_INDEX)
                vValues[jVertexId_get * lenMarkID + index] = jDis;
        }

        vertices.at(jVertexId_get).isActive = jVertexActive_get;

        if (jVertexActive_get)
        {
            avSet.at(avCount) = jVertexId_get;
            avCount++;
        }
    }

    env->ReleaseLongArrayElements(jVertexId, VertexIDTemp, 0);
    env->ReleaseBooleanArrayElements(jVertexActive, VertexActiveTemp, 0);
    env->ReleaseDoubleArrayElements(jVertexAttr, VertexAttrTemp, 0);

/*
    // test for multithreading environment

    std::string outputT = std::string();

    for(int i = 0;i < vertexAllSum; i++){
        outputT += to_string(i) + ": {";
        for(int j = 0;j < lenMarkID; j++){
            outputT += " [" + to_string(execute.initVSet[j])+": "+to_string(execute.mValues[i * lenMarkID + j]) + "] ";
        }
        outputT += "}";
    }

    cout<<outputT<<endl;

    outputT.clear();
    // test end
*/

    chk = execute.update(vValues, &vertices[0], &avSet[0], avCount);

    if(chk == -1){
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

    //---------Time evaluating---------
    std::chrono::nanoseconds durationA = std::chrono::high_resolution_clock::now() - startTimeA;
    auto startTime = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    // execute using GPU in server-client mode

    execute.request();

    //---------Time evaluating---------
    std::chrono::nanoseconds duration = std::chrono::high_resolution_clock::now() - startTime;

    auto startTimeB = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    int activeCount = 0;
    bool allGained = true;
    for (int i = 0; i < vertexAllSum; i++)
    {
        if (execute.vSet[i].isActive)
        {
            activeCount ++ ;
            // detect if the vertex is filtered
            if(! execute.filteredV[i])
            {
                allGained = false;
            }
        }
    }

    if(allGained) return static_cast<int>(0 - activeCount);
    else return static_cast<int>(activeCount);
}


JNIEXPORT jint JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_array_SSSP_GPUNative_nativeStepGetMessages
(JNIEnv * env, jobject superClass,
        jlong vertexSum, jlongArray returnId, jdoubleArray returnAttr,
        jint vertexCount, jint edgeCount, jint markIdLen, jint pid){

    //---------Time evaluating---------
    auto startTimeA = std::chrono::high_resolution_clock::now();


    string fileNameOutputEdgeLog = "testLogCPlusBreakDownPid" + to_string(pid)
                                   + "Time" + to_string(startTimeA.time_since_epoch().count()) + ".txt";
    string pathFile = "/usr/local/ssspexample/outputlog/";
    std::ofstream Tout(pathFile + fileNameOutputEdgeLog, fstream::out | fstream::app);

    //---------Time evaluating---------

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);
    int lenMarkID = static_cast<int>(markIdLen);
    int lenEdge = static_cast<int>(edgeCount);
    int lenVertex = static_cast<int>(vertexCount);

    UtilClient<double, double> execute = UtilClient<double, double>(vertexAllSum, lenEdge, lenMarkID, partitionID);

    int chk = 0;

    chk = execute.connect();
    if (chk == -1)
    {
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

/*
    // test for multithreading environment

    std::string outputT = std::string();

    for(int i = 0;i < vertexAllSum; i++){
        outputT += to_string(i) + ": {";
        for(int j = 0;j < lenMarkID; j++){
            outputT += " [" + to_string(execute.initVSet[j])+": "+to_string(execute.mValues[i * lenMarkID + j]) + "] ";
        }
        outputT += "}";
    }

    cout<<outputT<<endl;

    outputT.clear();
    // test end
*/

    //---------Time evaluating---------
    std::chrono::nanoseconds duration = std::chrono::high_resolution_clock::now() - startTimeA;

    auto startTimeB = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    vector<long> cPlusReturnId = vector<long>();
    vector<double> cPlusReturnAttr = vector<double>();

    for (int i = 0; i < vertexAllSum; i++)
    {
        if (execute.vSet[i].isActive)
        {
            // copy msg data that activated
            cPlusReturnId.emplace_back(i);
            for (int j = 0; j < lenMarkID; j++)
            {
                cPlusReturnAttr.emplace_back(execute.mValues[i * lenMarkID + j]);
            }
        }
    }

    env->SetLongArrayRegion(returnId, 0, cPlusReturnId.size(), &cPlusReturnId[0]);
    env->SetDoubleArrayRegion(returnAttr, 0, cPlusReturnAttr.size(), &cPlusReturnAttr[0]);

    execute.disconnect();

    //---------Time evaluating---------
    std::chrono::nanoseconds durationB = std::chrono::high_resolution_clock::now() - startTimeB;

    std::string output = std::string();
    output += "Time of partition " + to_string(pid) + " in fetching data in c++: "
              + to_string(duration.count()) + " " + to_string(durationB.count());


    Tout<<output<<endl;

    Tout.close();

    //---------Time evaluating---------

    return static_cast<int>(cPlusReturnId.size());
}


JNIEXPORT jint JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_array_SSSP_GPUNative_nativeStepGetOldMessages
(JNIEnv * env, jobject superClass,
         jlong vertexSum, jlongArray returnId, jbooleanArray returnActive, jintArray returnTimestamp, jdoubleArray returnAttr,
         jint vertexCount, jint edgeCount, jint markIdLen, jint pid) {

    //---------Time evaluating---------
    auto startTimeA = std::chrono::high_resolution_clock::now();


    string fileNameOutputEdgeLog = "testLogCPlusBreakDownPid" + to_string(pid)
                                   + "Time" + to_string(startTimeA.time_since_epoch().count()) + ".txt";
    string pathFile = "/usr/local/ssspexample/outputlog/";
    std::ofstream Tout(pathFile + fileNameOutputEdgeLog, fstream::out | fstream::app);

    //---------Time evaluating---------

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);
    int lenMarkID = static_cast<int>(markIdLen);
    int lenEdge = static_cast<int>(edgeCount);
    int lenVertex = static_cast<int>(vertexCount);

    UtilClient<double, double> execute = UtilClient<double, double>(vertexAllSum, lenEdge, lenMarkID, partitionID);

    int chk = 0;

    chk = execute.connect();
    if (chk == -1)
    {
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

/*
    // test for multithreading environment

    std::string outputT = std::string();

    for(int i = 0;i < vertexAllSum; i++){
        outputT += to_string(i) + ": {";
        for(int j = 0;j < lenMarkID; j++){
            outputT += " [" + to_string(execute.initVSet[j])+": "+to_string(execute.mValues[i * lenMarkID + j]) + "] ";
        }
        outputT += "}";
    }

    cout<<outputT<<endl;

    outputT.clear();
    // test end
*/

    //---------Time evaluating---------
    std::chrono::nanoseconds duration = std::chrono::high_resolution_clock::now() - startTimeA;

    auto startTimeB = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    vector<long> cPlusReturnId = vector<long>();
    vector<jboolean> cPlusReturnActive = vector<jboolean>();
    vector<int> cPlusReturnTimeStamp = vector<int>();
    vector<double> cPlusReturnAttr = vector<double>();

    for (int i = 0; i < vertexAllSum; i++)
    {
        if (execute.timestamp[i] != -1)
        {
            // copy vertex data that once activated
            cPlusReturnId.emplace_back(i);
            cPlusReturnActive.emplace_back(execute.vSet[i].isActive);
            cPlusReturnTimeStamp.emplace_back(execute.timestamp[i]);
            for (int j = 0; j < lenMarkID; j++)
            {
                cPlusReturnAttr.emplace_back(execute.vValues[i * lenMarkID + j]);
            }
            // reset timestamp
            execute.timestamp[i] = -1;
        }
    }

    env->SetLongArrayRegion(returnId, 0, cPlusReturnId.size(), &cPlusReturnId[0]);
    env->SetBooleanArrayRegion(returnActive, 0, cPlusReturnActive.size(), &cPlusReturnActive[0]);
    env->SetIntArrayRegion(returnTimestamp, 0, cPlusReturnTimeStamp.size(), &cPlusReturnTimeStamp[0]);
    env->SetDoubleArrayRegion(returnAttr, 0, cPlusReturnAttr.size(), &cPlusReturnAttr[0]);

    execute.disconnect();

    //---------Time evaluating---------
    std::chrono::nanoseconds durationB = std::chrono::high_resolution_clock::now() - startTimeB;

    std::string output = std::string();
    output += "Time of partition " + to_string(pid) + " in fetching data in c++: "
              + to_string(duration.count()) + " " + to_string(durationB.count());

    /*
    Tout<<output<<endl;

    Tout.close();
     */
    //---------Time evaluating---------

    return static_cast<int>(cPlusReturnId.size());
}


JNIEXPORT jint JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_array_SSSP_GPUNative_nativeSkipVertexInput
(JNIEnv * env, jobject superClass,
         jlong vertexSum, jint vertexLen, jint edgeLen, jint markIdLen, jint pid, jint iterTimes){

    //---------Time evaluating---------
    auto startTimeA = std::chrono::high_resolution_clock::now();


    string fileNameOutputEdgeLog = "testLogCPlusBreakDownPid" + to_string(pid)
                                   + "Time" + to_string(startTimeA.time_since_epoch().count()) + ".txt";
    string pathFile = "/usr/local/ssspexample/outputlog/";
    std::ofstream Tout(pathFile + fileNameOutputEdgeLog, fstream::out | fstream::app);

    //---------Time evaluating---------

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);
    int lenMarkID = static_cast<int>(markIdLen);
    int lenEdge = static_cast<int>(edgeLen);

    UtilClient<double, double> execute = UtilClient<double, double>(vertexAllSum, lenEdge, lenMarkID, partitionID);

    int chk = 0;

    chk = execute.connect();
    if (chk == -1)
    {
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

    //---------Time evaluating---------
    std::chrono::nanoseconds durationA = std::chrono::high_resolution_clock::now() - startTimeA;
    auto startTime = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    execute.request();

    //---------Time evaluating---------
    std::chrono::nanoseconds duration = std::chrono::high_resolution_clock::now() - startTime;
    auto startTimeB = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    int activeCount = 0;
    bool allGained = true;
    for (int i = 0; i < vertexAllSum; i++)
    {
        if (execute.vSet[i].isActive)
        {
            activeCount ++ ;
            execute.timestamp[i] = iterTimes;
            // detect if the vertex is filtered
            if(! execute.filteredV[i])
            {
                allGained = false;
            }
        }
    }

    if(allGained) return static_cast<int>(0-activeCount);
    else return static_cast<int>(activeCount);
}

// server shutdown

JNIEXPORT jboolean JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_array_SSSP_GPUNative_nativeEnvClose
(JNIEnv * env, jobject superClass, jint pid){

    UtilClient<double, double> control = UtilClient<double, double>(0, 0, 0, pid);

    int chk = control.connect();
    if (chk == -1)
    {
        return false;
    }

    control.shutdown();
    return true;
}