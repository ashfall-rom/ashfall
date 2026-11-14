# AshFall RP5 hardware daemon (phase 5): fan + stick lighting.
# Included from rp5/rp5.mk.  Everything RP5-specific for the daemon lives in
# vendor/ashfall/rp5/hw/ (sources, init rc, SELinux label helpers).
#
# The daemon owns /sys/class/gpio5_pwm2/{state,duty,speed} and the
# /sys/class/sn3112{l,r}/led nodes; the AshFall Parts app only writes
# persist.ashfall.* properties (docs/phase5.md §2).
PRODUCT_PACKAGES += ashfall-hwd
