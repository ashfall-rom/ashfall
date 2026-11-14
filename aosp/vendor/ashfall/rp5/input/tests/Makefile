# Host-side unit tests for the ashfall-rp5-input extensions.
#
#   make -C aosp/vendor/ashfall/rp5/input/tests check
#
# Calibration files are redirected with $ASHFALL_CAL_DIR and the control
# socket with $ASHFALL_CONTROL_SOCKET, so no root and no device are needed.
CC ?= cc
CFLAGS ?= -std=gnu11 -O2 -g -Wall -Wextra -Wno-unused-parameter -DASHFALL_HOST
CPPFLAGS += -I..
LDLIBS += -lm

SRCS = test_input.c ../calib.c ../control.c ../input.c ../mapping.c ../plat.c \
       ../session.c
HDRS = ../calib.h ../control.h ../input.h ../mapping.h ../plat.h \
       ../session.h

test_input: $(SRCS) $(HDRS)
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $(SRCS) $(LDLIBS)

check: test_input
	./test_input

clean:
	rm -f test_input

.PHONY: check clean
