#ifndef STUDYLIB_H
#define STUDYLIB_H

#include <string>
#include <cstdint>

namespace studylib {

std::string hello(const char* name);

int add(int a, int b);

std::string getSystemInfo();

int8_t* createByteArray(int size, int* outLen);

std::string processIntArray(const int32_t* array, int len);

struct IntArrayResult {
    int32_t sum;
    int32_t min;
    int32_t max;
    double avg;
};

IntArrayResult calculateIntArrayStats(const int32_t* array, int len);

}

#endif