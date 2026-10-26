#!/bin/sh
exec xvfb-run -a --server-args="-screen 0 1920x1080x24" "$@"
