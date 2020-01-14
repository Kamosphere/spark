//
// Created by liqi on 19-6-14.
//

#ifndef PREGELGPU_INITPROBER_H
#define PREGELGPU_INITPROBER_H


#include "srv/UNIX_msg.h"

class initProber {
public:
    initProber(int nodeNo = 0);
    ~initProber();

    bool run();

    int nodeNo;

private:
    UNIX_msg init_msq;
};


#endif //PREGELGPU_INITPROBER_H
