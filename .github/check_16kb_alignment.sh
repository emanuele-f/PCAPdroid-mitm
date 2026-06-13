#!/usr/bin/env bash
#
# Verify that every native library (.so) bundled in the given APKs has its ELF
# LOAD segments aligned to 16 KB, as required to run on Android devices that use
# a 16 KB memory page size. See:
# https://developer.android.com/guide/practices/page-sizes
#
# Usage: check_16kb_alignment.sh APK [APK ...]

set -euo pipefail

if [ "$#" -eq 0 ]; then
    echo "Usage: $0 APK [APK ...]" >&2
    exit 2
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

status=0

for apk in "$@"; do
    echo "== $apk =="
    rm -rf "${workdir:?}/"*
    # Native libs live under lib/<abi>/*.so inside the APK.
    unzip -qo "$apk" 'lib/*.so' -d "$workdir" || true

    found=0
    while IFS= read -r -d '' so; do
        found=1
        rel="${so#"$workdir"/}"
        # A library is 16 KB compatible only if all of its LOAD segments have a
        # p_align that is a multiple of 16384 (0x4000). The last field of each
        # LOAD line is the alignment, printed by readelf as a hex literal.
        bad=""
        for align in $(readelf -lW "$so" | awk '$1 == "LOAD" { print $NF }'); do
            # Bash arithmetic understands the 0x prefix, so no gawk strtonum needed.
            if (( align % 16384 != 0 )); then
                bad="$bad $align"
            fi
        done
        if [ -n "$bad" ]; then
            echo "  NOT 16 KB aligned: $rel (LOAD align:$bad)"
            status=1
        else
            echo "  OK: $rel"
        fi
    done < <(find "$workdir" -name '*.so' -print0)

    if [ "$found" -eq 0 ]; then
        echo "  ERROR: no native libraries found in APK" >&2
        status=1
    fi
done

if [ "$status" -ne 0 ]; then
    echo "FAILED: some native libraries are not 16 KB aligned" >&2
fi

exit "$status"
