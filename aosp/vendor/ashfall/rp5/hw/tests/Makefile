# Host-side unit tests for the ashfall-hwd logic (no device needed).
#
#   make -C aosp/vendor/ashfall/rp5/hw/tests check
#
# The sources are compiled with -DASHFALL_HOST: properties come from the
# environment and sysfs paths are rooted under $ASHFALL_SYSFS_ROOT.
CC ?= cc
CFLAGS ?= -std=gnu11 -O2 -g -Wall -Wextra -Wno-unused-parameter -DASHFALL_HOST
CPPFLAGS += -I..
LDLIBS += -lm

SRCS = test_hw.c ../fan.c ../gpu.c ../led.c ../plat.c ../thermal.c ../throttle.c
HDRS = ../fan.h ../gpu.h ../led.h ../plat.h ../thermal.h ../throttle.h

test_hw: $(SRCS) $(HDRS)
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $(SRCS) $(LDLIBS)

check: test_hw
	./test_hw

clean:
	rm -f test_hw

.PHONY: check clean
