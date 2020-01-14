#include "edu_ustc_nodb_GPUGraphX_algorithm_shm_PageRank_GPUNativeShm.h"

#include "util/JNIPlugin.h"

#include <sys/mman.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <zconf.h>
#include <algorithm>
#include <algo/PageRank/PageRank.h>
#include <unordered_map>

using namespace std;

template <typename T>
int openShm(const string &filename, int counter, T* &Arr)
{

    int counterSize = counter * sizeof(T);

    int chk = shm_open(filename.c_str(), O_RDWR, 0664);

    if(chk == -1) {
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

    if(chk == -1) {
        cout << errno << endl;
        return -1;
    }

    void* ptr = mmap(nullptr, counterSize, PROT_READ | PROT_WRITE, MAP_SHARED, chk, 0);

    int ft = ftruncate(chk, counterSize);
    if(ft == -1) {
        cout << errno << endl;
        return -1;
    }

    memcpy((T* )ptr, ArrAddress, counterSize);

    return 0;
}

// Init the edge and markID

JNIEXPORT jboolean JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_PageRank_GPUNativeShm_nativeEnvEdgeInit
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

    //---------Entity---------

    int lenMarkID = env->CallIntMethod(markId, id_ArrayList_size);
    int lenFiltered = env->GetArrayLength(jFilteredVertex);

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);

    int chk = 0;
    //Init the Graph with blank vertices

    vector<Vertex> vertices = vector<Vertex>();
    std::pair<double, double> *vValues = new std::pair<double, double> [vertexAllSum]();

    bool* filteredV = new bool [vertexAllSum]();

    int* timestamp = new int [vertexAllSum];
    memset(timestamp, -1, sizeof(int) * vertexAllSum);

    vector<Edge> edges = vector<Edge>();

    for(int i = 0; i < vertexAllSum; i++){
        vertices.emplace_back(Vertex(i, false, INVALID_INITV_INDEX));
    }

    // fill markID, which stored the landmark

    int *initVSet = new int [lenMarkID];

    for(int i = 0; i < lenMarkID; i++){

        jobject start = env->CallObjectMethod(markId, id_ArrayList_get, i);
        jlong jMarkIDUnit = env->CallLongMethod(start, longValue);
        if (jMarkIDUnit > 0) {
            vertices.at(jMarkIDUnit).initVIndex = i;
            initVSet[i] = jMarkIDUnit;
        }

        // Special situation if all src is required to calculate
        if (jMarkIDUnit == -1) {
            initVSet[i] = jMarkIDUnit;
        }
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

    if(sizeSrcID != sizeDstID || sizeDstID != sizeAttr){
        throwIllegalArgument(env, "Shared memory align corruption");
    }
    int lenEdge = sizeAttr;

    chk = openShm(EdgeSrcTempName, sizeSrcID, EdgeSrcTemp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    chk = openShm(EdgeDstTempName, sizeDstID, EdgeDstTemp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    chk = openShm(EdgeAttrTempName, sizeAttr, EdgeAttrTemp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    for(int i = 0; i < lenFiltered; i++){
        filteredV[FilteredVertexTemp[i]] = true;
    }

    for (int i = 0; i < lenEdge; i++) {

        int jSrcId_get = EdgeSrcTemp[i];
        int jDstId_get = EdgeDstTemp[i];
        double jAttr_get = EdgeAttrTemp[i];

        edges.emplace_back(Edge(jSrcId_get, jDstId_get, jAttr_get));

    }

    initProber detector = initProber(partitionID);
    bool status = detector.run();
    if(! status) {
        throwIllegalArgument(env, "Cannot detect existing server");
        return false;
    }

    UtilClient<std::pair<double, double>, PRA_MSG> execute =
            UtilClient<std::pair<double, double>, PRA_MSG>
            (vertexAllSum, lenEdge, lenMarkID, partitionID);

    chk = execute.connect();
    if (chk == -1){
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
        return false;
    }

    chk = execute.transfer(vValues, &vertices[0], &edges[0], initVSet, filteredV, timestamp);

    if(chk == -1){
        throwIllegalArgument(env, "Cannot transfer with server correctly");
        return false;
    }
    execute.disconnect();

    shm_unlink(EdgeSrcTempName.c_str());
    shm_unlink(EdgeDstTempName.c_str());
    shm_unlink(EdgeAttrTempName.c_str());

    return true;
}


JNIEXPORT jint JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_PageRank_GPUNativeShm_nativeStepMsgExecute
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

    UtilClient<std::pair<double, double>, PRA_MSG> execute = UtilClient<std::pair<double, double>, PRA_MSG>
            (vertexAllSum, lenEdge, lenMarkID, partitionID);

    int chk = 0;

    chk = execute.connect();
    if (chk == -1){
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

    // Init the vertices

    vector<Vertex> vertices = vector<Vertex>();
    std::pair<double, double> *vValues = new std::pair<double, double> [vertexAllSum]();

    for(int i = 0; i < vertexAllSum; i++){
        vertices.emplace_back(Vertex(i, false, INVALID_INITV_INDEX));
    }

    for(jint i = 0; i < lenMarkID; i++){
        if(execute.initVSet[i] != -1){
            vertices.at(execute.initVSet[i]).initVIndex = i;
        }
    }

    // read vertices attributes from shm files
    long* VertexIDTemp;
    bool * VertexActiveTemp;
    double* VertexAttr1Temp;
    double* VertexAttr2Temp;

    string VertexIDTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 0));
    string VertexActiveTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 1));
    string VertexAttr1TempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 2));
    string VertexAttr2TempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 3));

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

    chk = openShm(VertexAttr1TempName, sizeAttr, VertexAttr1Temp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    chk = openShm(VertexAttr2TempName, sizeAttr, VertexAttr2Temp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    for (int i = 0; i < sizeID; i++) {

        // jlong sig = env->CallStaticLongMethod(n_sztool, id_getSize, vertexLL);

        long jVertexId_get = VertexIDTemp[i];
        bool jVertexActive_get = VertexActiveTemp[i];

        vValues[jVertexId_get] = std::pair<double, double>(VertexAttr1Temp[i], VertexAttr2Temp[i]);

        vertices.at(jVertexId_get).isActive = jVertexActive_get;

    }

    // test for multi-threading environment
/*
    auto startTimeAll = std::chrono::high_resolution_clock::now();
    string pathFileA = "/home/liqi/IdeaProjects/GraphXwithGPU/logPageRankGPU/1/";
    string fileNameOutputVertexLog = "testLogCPlusVertexPid" + to_string(pid) + "time" +
                                   to_string(startTimeAll.time_since_epoch().count()) + ".txt";
    std::ofstream Fout(pathFileA + fileNameOutputVertexLog, fstream::out | fstream::app);

    Fout<<"-----------------Before-----------------"<<endl;

    for(int i = 0;i < vertexAllSum; i++){
        std::string outputT = "In partition " + to_string(pid) + " , ";
        outputT += to_string(i) + " active status: " + to_string(execute.vSet[i].isActive) + " : {";
        outputT += " [ " + to_string(execute.vValues[i].first) + " ] [ " + to_string(execute.vValues[i].second) + " ] ";
        outputT += " } ";
        Fout<<outputT<<endl;

    }

    Fout<<"-----------------Before-----------------"<<endl;
    Fout.close();
*/
    chk = execute.update(vValues, &vertices[0]);

    if(chk == -1){
        throwIllegalArgument(env, "Cannot update with server correctly");
    }
/*
    auto startTimeAll2 = std::chrono::high_resolution_clock::now();
    string pathFileB = "/home/liqi/IdeaProjects/GraphXwithGPU/logPageRankGPU/2/";
    string fileNameOutputVertexLog2 = "testLogCPlusVertexPid" + to_string(pid) + "time" +
                                   to_string(startTimeAll2.time_since_epoch().count()) + ".txt";
    std::ofstream Gout(pathFileB + fileNameOutputVertexLog2, fstream::out | fstream::app);

    Gout<<"-----------------Before-----------------"<<endl;

    for(int i = 0;i < vertexAllSum; i++){
        std::string outputT = "In partition " + to_string(pid) + " , ";
        outputT += to_string(i) + " active status: " + to_string(execute.vSet[i].isActive) + " : {";
        outputT += " [ " + to_string(execute.vValues[i].first) + " ] [ " + to_string(execute.vValues[i].second) + " ] ";
        outputT += " } ";
        Gout<<outputT<<endl;

    }

    Gout<<"-----------------Before-----------------"<<endl;
    Gout.close();
*/
/*
    string fileNameOutputEdgeLog = "testLogCPlusEdgePid" + to_string(pid) + "time" +
                               to_string(startTimeAll.time_since_epoch().count()) + ".txt";
    std::ofstream Fout(pathFile + fileNameOutputEdgeLog, fstream::out | fstream::app);

    Fout<<"-----------------Before-----------------"<<endl;

    for(int i = 0;i < edgeCount; i++){
        std::string outputT = "In partition " + to_string(pid) + " , ";
        outputT += " [ " + to_string(execute.eSet[i].src) + " -> " + to_string(execute.eSet[i].dst) + " : ";
        outputT += ": " + to_string(execute.eSet[i].weight) + " : " + to_string(execute.eSet[i].posOfMValues) + " ] ";
        Fout<<outputT<<endl;

    }

    Fout<<"-----------------Before-----------------"<<endl;
*/

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
/*
    auto startTimeAll3 = std::chrono::high_resolution_clock::now();
    string pathFile3 = "/home/liqi/IdeaProjects/GraphXwithGPU/logPageRankGPU/3/";
    string fileNameOutputVertexLog3 = "testLogCPlusVertexPid" + to_string(pid) + "time" +
                                      to_string(startTimeAll3.time_since_epoch().count()) + ".txt";
    std::ofstream Hout(pathFile3 + fileNameOutputVertexLog3, fstream::out | fstream::app);

    Hout<<"-----------------After-----------------"<<endl;

    for(int i = 0;i < vertexAllSum; i++){
        std::string outputT = "In partition " + to_string(pid) + " , ";
        outputT += to_string(i) + " active status: " + to_string(execute.vSet[i].isActive) + " : {";
        outputT += " [ " + to_string(execute.vValues[i].first) + " ] [ " + to_string(execute.vValues[i].second) + " ] ";
        outputT += " } ";
        Hout<<outputT<<endl;

    }

    Hout<<"-----------------After-----------------"<<endl;
    Hout.close();


    auto startTimeAll4 = std::chrono::high_resolution_clock::now();
    string pathFile4 = "/home/liqi/IdeaProjects/GraphXwithGPU/logPageRankGPU/4/";
    string fileNameOutputResultLog4 = "testLogCPlusResultPid" + to_string(pid) + "time" +
                               to_string(startTimeAll4.time_since_epoch().count()) + ".txt";
    std::ofstream kout(pathFile4 + fileNameOutputResultLog4, fstream::out | fstream::app);

    kout<<"-----------------After-----------------"<<endl;

    for (int i = 0; i < execute.eCount; i++) {
        int messageVertex = execute.mValues[i].destVId;
        if (messageVertex != -1) {
            std::string outputT = "In partition " + to_string(pid) + " , ";
            outputT += to_string(messageVertex) + " : {";
            outputT += " [ " + to_string(execute.mValues[i].rank) + " ] ";
            outputT += " } ";
            kout << outputT << endl;
        }
    }

    kout<<"-----------------After-----------------"<<endl;
    kout.close();

*/

    vector<long> cPlusReturnId = vector<long>();
    vector<double> cPlusReturnAttr = vector<double>();

    bool allGained = true;
    for (int i = 0; i < vertexAllSum; i++) {
        if (execute.mValues[i].destVId != -1) {
            if (execute.vSet[execute.mValues[i].destVId].isActive) {
                // copy data
                cPlusReturnId.emplace_back(execute.mValues[i].destVId);
                cPlusReturnAttr.emplace_back(execute.mValues[i].rank);
                // detect if the vertex is filtered
                if(! execute.filteredV[execute.mValues[i].destVId]){
                    allGained = false;
                }
            }
        }
    }

    // name the returned shm file
    string resultAttrIdentifier = to_string(pid) + "Double" + "ResultsMsg";
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

JNIEXPORT jboolean JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_PageRank_GPUNativeShm_nativeEnvClose
        (JNIEnv * env, jobject superClass, jint pid){

    UtilClient<pair<double, double>, PRA_MSG> control =
            UtilClient<pair<double, double>, PRA_MSG>(0, 0, 0, pid);

    int chk = control.connect();
    if (chk == -1){
        return false;
    }

    control.shutdown();
    return true;
}