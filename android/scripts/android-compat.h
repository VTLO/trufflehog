/* Shims for libc functions git expects but Android's bionic does not provide. */
#ifndef VTLO_ANDROID_COMPAT_H
#define VTLO_ANDROID_COMPAT_H
#include <pthread.h>
#ifndef PTHREAD_CANCEL_DISABLE
#define PTHREAD_CANCEL_ENABLE 0
#define PTHREAD_CANCEL_DISABLE 1
/* bionic has no thread cancellation, so there is no state to change. */
static inline int vtlo_pthread_setcancelstate(int state, int *old) {
	(void)state;
	if (old) *old = PTHREAD_CANCEL_ENABLE;
	return 0;
}
#define pthread_setcancelstate vtlo_pthread_setcancelstate
#endif
#endif
