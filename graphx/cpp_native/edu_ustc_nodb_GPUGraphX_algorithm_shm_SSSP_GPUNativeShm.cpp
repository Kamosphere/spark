#include "edu_ustc_nodb_GPUGraphX_algorithm_shm_SSSP_GPUNativeShm.h"

#include "util/JNIPlugin.h"

#include <sys/mman.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <zconf.h>
#include <algorithm>

using namespace std;

template <typename T>
int openShm(const string &filename, int counter, T* &Arr)
{

    int counterSize = counter * sizeof(T);
    int chk = shm_open(filename.c_str(), O_RDWR, 0664);

    if(chk == -1)
    {
        cout << errno << endl;
        return -1;
    }

    void* ptr = mmap(nullptr, counterSize, PROT_READ | PROT_WRITE, MAP_SHARED, chk, 0);
    Arr = (T* )ptr;

    return 0;
}

template <typename T>
int writeShm(const string &filename, vector<T> &Arr)
{

    int counterSize = Arr.size() * sizeof(T);
    T* ArrAddress = &Arr[0];

    int chk = shm_open(filename.c_str(), O_RDWR | O_CREAT, 0664);

    if(chk == -1)
    {
        cout << errno << endl;
        return -1;
    }

    void* ptr = mmap(nullptr, counterSize, PROT_READ | PROT_WRITE, MAP_SHARED, chk, 0);
    int ft = ftruncate(chk, counterSize);
    if(ft == -1)
    {
        cout << errno << endl;
        return -1;
    }
    memcpy((T* )ptr, ArrAddress, counterSize);

    return 0;
}

// Init the edge and markID

JNIEXPORT jboolean JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_SSSP_GPUNativeShm_nativeEnvEdgeInit
(JNIEnv * env, jobject superClass,
        jlongArray jFilteredVertex, jlong vertexSum, jobject markId, jint pid, jobject shmReader){

    jclass c_Long = env->FindClass("java/lang/Long");
    jmethodID longValue = env->GetMethodID(c_Long, "longValue", "()J");

    jclass c_ArrayList = env->FindClass("java/util/ArrayList");
    jmethodID id_ArrayList_size = env->GetMethodID(c_ArrayList, "size", "()I");
    jmethodID id_ArrayList_get = env->GetMethodID(c_ArrayList, "get", "(I)Ljava/lang/Object;");

    jclass readerClass = env->GetObjectClass(shmReader);
    jmethodID getReaderName = env->GetMethodID(readerClass, "getNameByIndex", "(I)Ljava/lang/String;");
    jmethodID getReaderSize = env->GetMethodID(readerClass, "getSizeByIndex", "(I)I");


    string fileNameOutputEdgeLog = "testTransfer" + to_string(pid) + ".txt";
    string pathFile = "/usr/local/ssspexample/outputlog/";
    std::ofstream Tout(pathFile + fileNameOutputEdgeLog, fstream::out | fstream::app);

    //---------Entity---------

    int lenMarkID = env->CallIntMethod(markId, id_ArrayList_size);
    int lenFiltered = env->GetArrayLength(jFilteredVertex);

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);

    int chk = 0;
    //Init the Graph with blank vertices

    vector<Vertex> vertices = vector<Vertex>();
    vector<Edge> edges = vector<Edge>();

    double *vValues = new double [vertexAllSum * lenMarkID];
    memset(vValues, INT32_MAX, sizeof(double) * vertexAllSum * lenMarkID);

    bool* filteredV = new bool [vertexAllSum]();

    int* timestamp = new int [vertexAllSum];
    memset(timestamp, -1, sizeof(int) * vertexAllSum);

    for(int i = 0; i < vertexAllSum; i++)
    {
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

    // read vertices attributes from shm files
    long* EdgeSrcTemp;
    long* EdgeDstTemp;
    double* EdgeAttrTemp;

    string EdgeSrcTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 0));
    string EdgeDstTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 1));
    string EdgeAttrTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 2));

    int sizeSrcID = env->CallIntMethod(shmReader, getReaderSize, 0);
    int sizeDstID = env->CallIntMethod(shmReader, getReaderSize, 1);
    int sizeAttr = env->CallIntMethod(shmReader, getReaderSize, 2);

    if(sizeSrcID != sizeDstID || sizeDstID != sizeAttr)
    {
        throwIllegalArgument(env, "Shared memory align corruption");
    }
    int lenEdge = sizeAttr;

    chk = openShm(EdgeSrcTempName, sizeSrcID, EdgeSrcTemp);
    if (chk == -1)
    {
        throwIllegalArgument(env, "Shared memory corruption");
    }

    chk = openShm(EdgeDstTempName, sizeDstID, EdgeDstTemp);
    if (chk == -1)
    {
        throwIllegalArgument(env, "Shared memory corruption");
    }

    chk = openShm(EdgeAttrTempName, sizeAttr, EdgeAttrTemp);
    if (chk == -1)
    {
        throwIllegalArgument(env, "Shared memory corruption");
    }

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

    initProber detector = initProber(partitionID);
    bool status = detector.run();
    if(! status)
    {
        throwIllegalArgument(env, "Cannot detect existing server");
        return false;
    }

    UtilClient<double, double> execute = UtilClient<double, double>(vertexAllSum, lenEdge, lenMarkID, partitionID);

    chk = execute.connect();
    if (chk == -1)
    {
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
        return false;
    }

    //---------Time evaluating---------
    auto startTime = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    // execute sssp using GPU in server-client mode

    chk = execute.transfer(vValues, &vertices[0], &edges[0], initVSet, filteredV, timestamp);

    //---------Time evaluating---------
    std::chrono::nanoseconds duration = std::chrono::high_resolution_clock::now() - startTime;

    Tout << duration.count() << endl;

    if(chk == -1)
    {
        throwIllegalArgument(env, "Cannot transfer with server correctly");
        return false;
    }

    Tout.close();

/*
    string fileNameOutputEdgeLog = "testLogCPlusEdgePid" + to_string(pid) + ".txt";
    string pathFile = "/usr/local/ssspexample/outputlog/";
    std::ofstream Fout(pathFile + fileNameOutputEdgeLog, fstream::out | fstream::app);

    for(int i = 0;i < execute.eCount; i++){
        std::string outputT ;
        outputT += to_string(execute.eSet[i].src) + " " + to_string(execute.eSet[i].dst) + " " + to_string(execute.eSet[i].weight);
        Fout<<outputT<<endl;
    }

    Fout.close();
*/
    execute.disconnect();

    shm_unlink(EdgeSrcTempName.c_str());
    shm_unlink(EdgeDstTempName.c_str());
    shm_unlink(EdgeAttrTempName.c_str());

    return true;
}


JNIEXPORT jint JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_SSSP_GPUNativeShm_nativeStepMsgExecute
(JNIEnv * env, jobject superClass,
        jlong vertexSum, jobject shmReader, jobject shmWriter,
        jint vertexCount, jint edgeCount, jint markIdLen, jint pid){

    jclass readerClass = env->GetObjectClass(shmReader);
    jmethodID getReaderName = env->GetMethodID(readerClass, "getNameByIndex", "(I)Ljava/lang/String;");
    jmethodID getReaderSize = env->GetMethodID(readerClass, "getSizeByIndex", "(I)I");

    jclass writerClass = env->GetObjectClass(shmWriter);
    jmethodID addWriterName = env->GetMethodID(writerClass, "addName", "(Ljava/lang/String;I)Z");

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
    }

    // read vertices attributes from shm files
    long* VertexIDTemp;
    bool * VertexActiveTemp;
    double* VertexAttrTemp;

    string VertexIDTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 0));
    string VertexActiveTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 1));
    string VertexAttrTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 2));

    int sizeID = env->CallIntMethod(shmReader, getReaderSize, 0);
    int sizeActive = env->CallIntMethod(shmReader, getReaderSize, 1);
    int sizeAttr = env->CallIntMethod(shmReader, getReaderSize, 2);

    chk = openShm(VertexIDTempName, sizeID, VertexIDTemp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    chk = openShm(VertexActiveTempName, sizeActive, VertexActiveTemp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    chk = openShm(VertexAttrTempName, sizeAttr, VertexAttrTemp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    int avCount = 0;
    std::vector<int> avSet;
    avSet.reserve(vertexAllSum);
    avSet.assign(vertexAllSum, 0);

    for (int i = 0; i < sizeID; i++) {

        // jlong sig = env->CallStaticLongMethod(n_sztool, id_getSize, vertexLL);

        long jVertexId_get = VertexIDTemp[i];
        bool jVertexActive_get = VertexActiveTemp[i];

        for(int j = 0; j < lenMarkID; j++){
            double jDis = VertexAttrTemp[i * lenMarkID + j];
            int index = vertices.at(execute.initVSet[j]).initVIndex;
            if(index != INVALID_INITV_INDEX)vValues[jVertexId_get * lenMarkID + index] = jDis;
        }

        vertices.at(jVertexId_get).isActive = jVertexActive_get;

        if (jVertexActive_get)
        {
            avSet.at(avCount) = jVertexId_get;
            avCount++;
        }

    }

    // std::cout << "avCount: " << avCount << std::endl;

    /*
    for(jint i = 0; i < lenMarkID; i++)
    {
        cout << "partition " << pid << " " << execute.initVSet[i] << " " << vertices.at(execute.initVSet[i]).initVIndex<< endl;
        vValues[execute.initVSet[i] * lenMarkID + i] = 0;
    }
     */
/*
    auto startTimeAll = std::chrono::high_resolution_clock::now();
    string fileNameOutputLog = "testLogCPlusPid" + to_string(pid) + "time" +
                               to_string(startTimeAll.time_since_epoch().count()) + ".txt";
    string pathFile2 = "/home/liqi/IdeaProjects/GraphXwithGPU/logSSSPGPU/";
    std::ofstream Gout(pathFile2 + fileNameOutputLog, fstream::out | fstream::app);

    Gout<<"-----------------Before-----------------"<<endl;

    for(int i = 0;i < vertexAllSum; i++){
        if (vertices[i].isActive) {
            std::string outputT = "In partition " + to_string(pid) + " , ";
            outputT += to_string(i) + " active status: " + to_string(vertices[i].isActive) + " : {";
            for (int j = 0 ; j < markIdLen; j++){
                outputT += " [ " + to_string(vValues[i * markIdLen + j]) + " ] ";
            }
            outputT += " } ";
            Gout<<outputT<<endl;
        }
    }

    Gout<<"-----------------Before-----------------"<<endl;
    Gout.close();
*/
    chk = execute.update(vValues, &vertices[0], &avSet[0], avCount);

    if(chk == -1){
        throwIllegalArgument(env, "Cannot update with server correctly");
    }

    /*
    // test for multithreading environment

    std::ofstream Gout("testGraph100Log" + to_string(pid) + ".txt", fstream::out | fstream::app);

    Gout<<"-----------------Before-----------------"<<endl;

    for(int i = 0;i < vertexAllSum; i++){
        std::string outputT = "In partition " + to_string(pid) + " , ";
        outputT += to_string(i) + " active status: " + to_string(execute.vSet[i].isActive) + " : {";
        for(int j = 0;j < lenMarkID; j++){
            outputT += " [ " + to_string(execute.initVSet[j])+" : "+to_string(execute.vValues[i * lenMarkID + j]) + " ] ";
        }
        outputT += " } ";
        if(i == 100){
            Gout<<outputT<<endl;
        }
    }

    Gout<<"-----------------Before-----------------"<<endl;

    // test end
    */

    //---------Time evaluating---------
    std::chrono::nanoseconds durationA = std::chrono::high_resolution_clock::now() - startTimeA;
    auto startTime = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    // execute sssp using GPU in server-client mode

    execute.request();

/*
    auto startTimeAll3 = std::chrono::high_resolution_clock::now();
    string fileNameOutputLog3 = "testLogCAfterPlusPid" + to_string(pid) + "time" +
                               to_string(startTimeAll.time_since_epoch().count()) + ".txt";
    string pathFile3 = "/home/liqi/IdeaProjects/GraphXwithGPU/logSSSPGPU/";
    std::ofstream Gzout(pathFile3 + fileNameOutputLog3, fstream::out | fstream::app);

    Gzout<<"-----------------after-----------------"<<endl;

    for(int i = 0;i < vertexAllSum; i++){
        if (execute.vSet[i].isActive) {
            std::string outputT = "In partition " + to_string(pid) + " , ";
            outputT += to_string(i) + " active status: " + to_string(execute.vSet[i].isActive) + " : {";
            for (int j = 0 ; j < markIdLen; j++){
                outputT += " [ " + to_string(execute.vValues[i * markIdLen + j]) + " ] ";
            }
            outputT += " } ";
            Gzout<<outputT<<endl;
        }

    }

    Gzout<<"-----------------after-----------------"<<endl;
    Gzout.close();
*/
    //---------Time evaluating---------
    std::chrono::nanoseconds duration = std::chrono::high_resolution_clock::now() - startTime;

    auto startTimeB = std::chrono::high_resolution_clock::now();
    //---------Time evaluating---------

    vector<long> cPlusReturnId = vector<long>();
    vector<double> cPlusReturnAttr = vector<double>();

    bool allGained = true;
    for (int i = 0; i < vertexAllSum; i++) {
        if (execute.vSet[i].isActive) {
            // copy data
            cPlusReturnId.emplace_back(i);
            for (int j = 0; j < lenMarkID; j++) {
                cPlusReturnAttr.emplace_back(execute.mValues[i * lenMarkID + j]);
            }
            // detect if the vertex is filtered
            if(! execute.filteredV[i]){
                allGained = false;
            }
        }
    }

    // name the returned shm file
    string resultAttrIdentifier = to_string(pid) + "Double" + "Results";
    string resultIdIdentifier = to_string(pid) + "Long" + "Results";

    chk = writeShm(resultIdIdentifier, cPlusReturnId);
    if(chk == -1){
        throwIllegalArgument(env, "Cannot construct shared data memory");
    }

    chk = writeShm(resultAttrIdentifier, cPlusReturnAttr);
    if(chk == -1){
        throwIllegalArgument(env, "Cannot construct shared data memory");
    }

    execute.disconnect();

    // fill them into scala object
    bool ifReturnId = env->CallObjectMethod(shmWriter, addWriterName, env->NewStringUTF(resultIdIdentifier.c_str()), cPlusReturnId.size());

    if(! ifReturnId){
        throwIllegalArgument(env, "Cannot write back identifier");
    }

    bool ifReturnAttr = env->CallObjectMethod(shmWriter, addWriterName, env->NewStringUTF(resultAttrIdentifier.c_str()), cPlusReturnAttr.size());

    if(! ifReturnAttr){
        throwIllegalArgument(env, "Cannot write back identifier");
    }

    //---------Time evaluating---------
    std::chrono::nanoseconds durationB = std::chrono::high_resolution_clock::now() - startTimeB;

    std::string output = std::string();
    output += "Time of partition " + to_string(pid) + " in c++: " + to_string(durationA.count()) + " "
              + to_string(duration.count()) + " " + to_string(durationB.count());

    Tout<<output<<endl;

    Tout.close();

    //---------Time evaluating---------

    if(allGained){
        return static_cast<int>(0-cPlusReturnId.size());
    }
    else{
        return static_cast<int>(cPlusReturnId.size());
    }

}

// server shutdown

JNIEXPORT jboolean JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_SSSP_GPUNativeShm_nativeEnvClose
  (JNIEnv * env, jobject superClass, jint pid){

    UtilClient<double, double> control = UtilClient<double, double>(0, 0, 0, pid);

    int chk = control.connect();
    if (chk == -1){
        return false;
    }

    control.shutdown();
    return true;
}