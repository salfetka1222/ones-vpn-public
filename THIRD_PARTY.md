# Native and third-party components

ONES VPN is an independent project. We do not imply official affiliation with the upstream sing-box authors.

## sing-box / libbox

- Upstream source: https://github.com/SagerNet/sing-box
- Release: v1.14.4, pinned commit `c005f35300ce76427dc285481ec5695f5b179cd2`
- License and upstream notice: [docs/licenses/sing-box-LICENSE](docs/licenses/sing-box-LICENSE) (GNU GPLv3 or later, plus the upstream additional notice)
- Distributed AAR SHA-256: `9b6d9a862907e77683ba1f00dbcb28c5d76f3e431c039f50f22a38f7b8471ede`
- The compiled AAR embeds Go 1.26.10 for Android ARM64. Its 113 module names, versions and h1 checksums match the included upstream `go.mod`/`go.sum` snapshot at `scripts/provenance/`.
- The corresponding upstream module source and the source build command are publicly available; `scripts/build-libbox-from-source.sh` builds libbox with Go 1.26.10 and Android NDK r28.
- Byte-for-byte binary reproducibility is not claimed; the original APK's signing key is not distributed.

## Other dependencies

The exact AndroidX, Compose and Kotlin dependencies are declared in the Gradle configuration. Go dependencies are identified in the locked source module files. Each third-party dependency remains subject to its respective license.

## Corresponding source

The full application source at tag `ones-vpn-stable-38` and the attached source archive correspond to the public release version 1.0.0 (code 38). Application release signing uses the developer's private key; modified/debug builds can be created from the public code using standard Android SDK tooling.

This summary is not an independent legal opinion or a claim that every transitive dependency license or vulnerability has been audited.
