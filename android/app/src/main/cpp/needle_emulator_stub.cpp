#include "needle.h"

extern "C" {

int needle_init(const char*, const char*, const char*) { return -1; }
int needle_complete(const char*, int, char*, int) { return -1; }
void needle_reset() {}
int needle_load(const unsigned char*, unsigned long long) { return -1; }

}
