#include "edu_ustc_nodb_GPUGraphX_algorithm_shm_LP_GPUNativeShm.h"

#include "util/JNIPlugin.h"

#include <sys/mman.h>
#include <fcntl.h>
#include <cerrno>
#include <cstring>
#include <zconf.h>
#include <algorithm>
#include <algo/LabelPropagation/LabelPropagation.h>
#include <unordered_map>

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


// Hash combine function to generate hash for pair
template <class T>
void hash_combine(std::size_t& seed, const T& v){
    std::hash<int> hasher;
    seed ^= hasher(v) + 0x9e3779b9 + (seed << 6) + (seed >> 2);
}

// Hash function for pair<T1, T2>, used in unordered_map
struct pair_hash {
    template <class T1, class T2>
    std::size_t operator () (const std::pair<T1, T2> &p) const {
        std::size_t result = 0;
        hash_combine(result, p.first);
        hash_combine(result, p.second);
        return result;
    }
};

using unordered_pairMap = unordered_map<pair<int, int>, int, pair_hash>;
using namespace std;

// Init the edge and markID

JNIEXPORT jboolean JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_LP_GPUNativeShm_nativeEnvEdgeInit
        (JNIEnv * env, jobject superClass,
         jlongArray jFilteredVertex, jlong vertexSum, jobject markId, jint pid, jobject shmReader){

    jclass readerClass = env->GetObjectClass(shmReader);
    jmethodID getReaderName = env->GetMethodID(readerClass, "getNameByIndex", "(I)Ljava/lang/String;");
    jmethodID getReaderSize = env->GetMethodID(readerClass, "getSizeByIndex", "(I)I");

    //---------Entity---------
    int lenFiltered = env->GetArrayLength(jFilteredVertex);

    int vertexAllSum = static_cast<int>(vertexSum);
    int partitionID = static_cast<int>(pid);

    int chk = 0;
    //Init the Graph with blank vertices
    vector<Vertex> vertices = vector<Vertex>();
    auto *vValues = new LPA_Value [vertexAllSum];

    bool* filteredV = new bool [vertexAllSum];
    memset(filteredV, false, sizeof(bool) * vertexAllSum);

    int* timestamp = new int [vertexAllSum];
    memset(timestamp, -1, sizeof(int) * vertexAllSum);

    vector<Edge> edges = vector<Edge>();

    for(int i = 0; i < vertexAllSum; i++){
        vertices.emplace_back(Vertex(i, false, INVALID_INITV_INDEX));
        vValues[i] = LPA_Value(i, i, 0);
    }

    // fill markID, which stored the landmark

    int *initVSet = new int [1];

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

    UtilClient<LPA_Value, LPA_MSG> execute = UtilClient<LPA_Value, LPA_MSG>
            (vertexAllSum, lenEdge, 1, partitionID);

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

    return true;
}


JNIEXPORT jint JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_LP_GPUNativeShm_nativeStepMsgExecute
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

    int lenEdge = static_cast<int>(edgeCount);
    int lenVertex = static_cast<int>(vertexCount);

    UtilClient<LPA_Value, LPA_MSG> execute = UtilClient<LPA_Value, LPA_MSG>
    (vertexAllSum, lenEdge, 1, partitionID);

    int chk = 0;
    chk = execute.connect();
    if (chk == -1){
        throwIllegalArgument(env, "Cannot establish the connection with server correctly");
    }

    // Init the vertices
    int maxLPA_ValueAmount = 0;

    if (lenEdge > vertexAllSum)
        maxLPA_ValueAmount = lenEdge;
    else
        maxLPA_ValueAmount = vertexAllSum;

    vector<Vertex> vertices = vector<Vertex>();
    auto *vValues = new LPA_Value [maxLPA_ValueAmount];

    for(int i = 0; i < vertexAllSum; i++){
        vertices.emplace_back(Vertex(i, false, INVALID_INITV_INDEX));
        vValues[i] = LPA_Value(i, i, 0);
    }

    for(int i = vertexAllSum; i < maxLPA_ValueAmount; i++) {
        vValues[i] = LPA_Value(-1, -1, -1);
    }

    // read vertices attributes from shm files
    long* VertexIDTemp;
    bool* VertexActiveTemp;
    long* VertexAttrKeyTemp;
    long* VertexAttrValueTemp;

    string VertexIDTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 0));
    string VertexActiveTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 1));
    string VertexAttrKeyTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 2));
    string VertexAttrValueTempName = jString2String(env, (jstring)env->CallObjectMethod(shmReader, getReaderName, 3));

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

    chk = openShm(VertexAttrKeyTempName, sizeAttr, VertexAttrKeyTemp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    chk = openShm(VertexAttrValueTempName, sizeAttr, VertexAttrValueTemp);
    if (chk == -1){
        throwIllegalArgument(env, "Shared memory corruption");
    }

    for (int i = 0; i < sizeID; i++) {

        // jlong sig = env->CallStaticLongMethod(n_sztool, id_getSize, vertexLL);

        long jVertexId_get = VertexIDTemp[i];
        bool jVertexActive_get = VertexActiveTemp[i];

        vValues[jVertexId_get] = LPA_Value(jVertexId_get, VertexAttrKeyTemp[i], VertexAttrValueTemp[i]);

        vertices.at(jVertexId_get).isActive = jVertexActive_get;

    }

    chk = execute.update(vValues, &vertices[0]);

    if(chk == -1){
        throwIllegalArgument(env, "Cannot update with server correctly");
    }

    // test for multithreading environment

/*
    auto startTimeAll = std::chrono::high_resolution_clock::now();
    string fileNameOutputLog = "testLogCPlusPid" + to_string(pid) + "time" +
                               to_string(startTimeAll.time_since_epoch().count()) + ".txt";
    string pathFile = "/home/liqi/IdeaProjects/GraphXwithGPU/logLPAGPU/";
    std::ofstream Gout(pathFile + fileNameOutputLog, fstream::out | fstream::app);

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

    // test end

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

    unordered_pairMap mergeMsg = unordered_pairMap(edgeCount);

    for (int i = 0; i < execute.eCount; i++) {
        if (execute.mValues[i].destVId != -1){
            pair<int, int> elem = make_pair(execute.mValues[i].destVId, execute.mValues[i].label);
            auto search = mergeMsg.find(elem);
            if (search != mergeMsg.end()){
                int count = search->second + 1;
                mergeMsg[elem] = count;
            }
            else {
                mergeMsg[elem] = 1;
            }
        }
    }

    vector<long> cPlusReturnId = vector<long>();
    vector<long> cPlusReturnAttr1 = vector<long>();
    vector<long> cPlusReturnAttr2 = vector<long>();

    bool allGained = true;

    for (auto i: mergeMsg){
        cPlusReturnId.emplace_back(i.first.first);
        cPlusReturnAttr1.emplace_back(i.first.second);
        cPlusReturnAttr2.emplace_back(i.second);

        if(! execute.filteredV[i.first.first]){
            allGained = false;
        }
    }

    // name the returned shm file
    string resultAttr1Identifier = to_string(pid) + "Long" + "Results1";
    string resultAttr2Identifier = to_string(pid) + "Long" + "Results2";
    string resultIdIdentifier = to_string(pid) + "Long" + "ResultsID";

    chk = writeShm(resultIdIdentifier, cPlusReturnId);
    if(chk == -1){
        throwIllegalArgument(env, "Cannot construct shared data memory");
    }

    chk = writeShm(resultAttr1Identifier, cPlusReturnAttr1);
    if(chk == -1){
        throwIllegalArgument(env, "Cannot construct shared data memory");
    }

    chk = writeShm(resultAttr2Identifier, cPlusReturnAttr2);
    if(chk == -1){
        throwIllegalArgument(env, "Cannot construct shared data memory");
    }

    execute.disconnect();

    // fill them into scala object
    bool ifReturnId = env->CallObjectMethod(shmWriter, addWriterName, env->NewStringUTF(resultIdIdentifier.c_str()), cPlusReturnId.size());

    if(! ifReturnId){
        throwIllegalArgument(env, "Cannot write back identifier");
    }

    bool ifReturnAttr1 = env->CallObjectMethod(shmWriter, addWriterName, env->NewStringUTF(resultAttr1Identifier.c_str()), cPlusReturnAttr1.size());

    if(! ifReturnAttr1){
        throwIllegalArgument(env, "Cannot write back identifier");
    }

    bool ifReturnAttr2 = env->CallObjectMethod(shmWriter, addWriterName, env->NewStringUTF(resultAttr2Identifier.c_str()), cPlusReturnAttr2.size());

    if(! ifReturnAttr2){
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

JNIEXPORT jboolean JNICALL Java_edu_ustc_nodb_GPUGraphX_algorithm_shm_LP_GPUNativeShm_nativeEnvClose
        (JNIEnv * env, jobject superClass, jint pid){

    UtilClient<LPA_Value, LPA_MSG> control = UtilClient<LPA_Value, LPA_MSG>
            (0, 0, 0, pid);

    int chk = control.connect();
    if (chk == -1){
        return false;
    }

    control.shutdown();
    return true;
}