#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
expected=9b6d9a862907e77683ba1f00dbcb28c5d76f3e431c039f50f22a38f7b8471ede
if ! test -s app/libs/libbox.aar; then
  curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 \
    'https://github.com/salfetka1222/ones-vpn-public/releases/download/ones-vpn-stable-38/libbox.aar' \
    -o app/libs/libbox.aar
fi
printf '%s  %s\n' "$expected" app/libs/libbox.aar | sha256sum -c -
python3 -B scripts/audit_libbox.py app/libs/libbox.aar
python3 -B scripts/audit_libbox_provenance.py app/libs/libbox.aar
