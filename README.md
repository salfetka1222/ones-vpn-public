# ONES VPN 1.0.0

Open-source Android VPN client for **Android 10+ / ARM64**, with Kotlin, Jetpack Compose and pinned sing-box/libbox 1.14.4.

**Version:** 1.0.0 · Android `versionCode 38`. The released APK is signed with the original ONES VPN signing certificate. ONES VPN is independent and is not affiliated with or endorsed by the sing-box project.

## Download

Get the APK from [the official GitHub Release](https://github.com/salfetka1222/ones-vpn-public/releases/tag/ones-vpn-stable-38). Download `ONES-VPN.apk`, optionally verify `SHA256SUMS.txt`, and install it over any older ONES VPN with the same signer. **Do not uninstall the existing app**, since uninstalling can erase saved profiles.

## Features

Encrypted proxy profiles over Android `VpnService`; server selection and Smart Connect; IPv4/IPv6 routing and DNS audit; per-app Split Tunneling; session and traffic statistics; Quick Settings tile; in-app update center; password-encrypted profile backup and restore.

## Privacy and limitations

**System Kill Switch is not guaranteed.** Always-on VPN and *Block connections without VPN* (Lockdown) are Android system settings, not controls that ONES VPN can enforce after its process terminates. Some Vivo/iQOO OriginOS builds do not show those switches. If Lockdown is off, ordinary internet traffic may resume when the VPN process or tunnel stops. Do not rely on ONES VPN for guaranteed fail-closed network isolation on such devices.

Apps intentionally excluded using Split Tunneling can bypass ONES VPN and reveal their normal network address/DNS. No public WebRTC IP leak was observed in one tested non-excluded browser session, but this does not guarantee behavior for every app or failure condition.

## Build from source

You need JDK 17, Android SDK 35 (Build Tools 35.0.0), a Linux/macOS Bash environment, Python 3 and Gradle 8.13 (wrapper included).

```sh
git clone https://github.com/salfetka1222/ones-vpn-public.git
cd ones-vpn-public
bash scripts/fetch-libbox.sh
python3 -B -m unittest discover -s scripts -p 'test_*.py'
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The published native `libbox.aar` is downloaded from this release and checked against its exact SHA-256, AArch64 ELF identity and pinned upstream Go module metadata. To **build the native library from source**, use `scripts/build-libbox-from-source.sh` on Linux with Go 1.26.10 and Android NDK r28. The source-based build may not have a byte-identical hash; with your own native library in `app/libs/`, run Gradle directly.

Source corresponding to the public APK is available at the release tag and in the release source archive. The source contains no private signing key, user profiles or subscription credentials.

## License

ONES VPN is licensed under **GNU GPL version 3 or later**. The full GPLv3 text is in [LICENSE](LICENSE). The native engine is based on [SagerNet/sing-box](https://github.com/SagerNet/sing-box), v1.14.4 at commit `c005f35300ce76427dc285481ec5695f5b179cd2`; see [the upstream notice](docs/licenses/sing-box-LICENSE) and [third-party information](THIRD_PARTY.md). Third-party packages retain their own licenses.

Project channel: https://t.me/onesvpn_official
