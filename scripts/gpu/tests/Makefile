# Host-side tests for the phase-6 GPU DTB tooling (no device needed).
#
#   make -C scripts/gpu/tests check
#
# The DTS/DTS tests are pure Python; the FDT splice tests compile fixtures with
# the distro dtc and are skipped when it is not on PATH.

PYTHON ?= python3

check:
	$(PYTHON) -m unittest -v test_dtb_oc test_vb_image test_sepolicy_gpu

clean:
	rm -rf __pycache__

.PHONY: check clean
