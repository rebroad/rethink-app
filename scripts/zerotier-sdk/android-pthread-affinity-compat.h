#pragma once

#include <cstddef>
#include <pthread.h>
#include <sched.h>

#if defined(__ANDROID__)
// Android's NDK does not expose pthread_setaffinity_np; the SDK does not
// require CPU pinning, so treat the optional affinity request as a no-op.
static inline int pthread_setaffinity_np(pthread_t, size_t, const cpu_set_t*)
{
	return 0;
}
#endif
