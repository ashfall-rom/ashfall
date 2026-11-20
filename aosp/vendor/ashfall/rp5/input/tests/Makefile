# Host-side unit tests for the ashfall-rp5-input extensions.
#
#   make -C aosp/vendor/ashfall/rp5/input/tests check
#
# Calibration files are redirected with $ASHFALL_CAL_DIR and the control
# socket with $ASHFALL_CONTROL_SOCKET, so no root and no device are needed.
# fusion.c is compiled in too: its registry/merge/remap/settings logic is the
# part that must not depend on hardware (#138).  shadow.c is the per-pad
# pass-through device logic the desktop pointer relies on (#250).
CC ?= cc
CFLAGS ?= -std=gnu11 -O2 -g -Wall -Wextra -Wno-unused-parameter -DASHFALL_HOST
CPPFLAGS += -I..
LDLIBS += -lm

SRCS = test_input.c ../calib.c ../control.c ../fusion.c ../input.c \
       ../mapping.c ../plat.c ../pointer.c ../session.c ../shadow.c \
       ../uinput_dev.c
HDRS = ../calib.h ../control.h ../fusion.h ../input.h ../mapping.h \
       ../plat.h ../pointer.h ../session.h ../shadow.h ../uinput_dev.h

test_input: $(SRCS) $(HDRS)
	$(CC) $(CPPFLAGS) $(CFLAGS) -o $@ $(SRCS) $(LDLIBS)

check: test_input
	./test_input

clean:
	rm -f test_input

.PHONY: check clean
