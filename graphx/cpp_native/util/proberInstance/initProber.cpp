//
// Created by liqi on 19-6-14.
//

#include <iostream>
#include "initProber.h"
#include "include/UNIX_macro.h"

initProber::initProber(int nodeNo) {
    //Test
    std::cout << "Detecting server status" << std::endl;
    //Test end

    this->nodeNo = nodeNo;
    this->init_msq = UNIX_msg();
}

bool initProber::run() {

    int ret = -1;
    int retryTimes = 10;
    struct timespec ts;

    while(ret == -1 && retryTimes > 0){
        ts = {0, (10-retryTimes) * 10000000};
        nanosleep(&ts, nullptr);
        retryTimes--;

        key_t use = ((this->nodeNo << NODE_NUM_OFFSET) | (INIT_MSG_TYPE << MSG_TYPE_OFFSET));

        ret = this->init_msq.fetch(use);
    }

    if(retryTimes != 0){
        char tmp[256];
        std::string cmd = std::string("");

        this->init_msq.recv(tmp, (INIT_MSG_TYPE << MSG_TYPE_OFFSET), 256);

        cmd = tmp;
        if(std::string("initiated") == cmd){
            return true;
        }
    }
    return false;
}

initProber::~initProber() {
    this->init_msq.control(IPC_RMID);
}