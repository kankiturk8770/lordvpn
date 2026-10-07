# Lord VPN (Android) - Xray client

Glass UI (6 themes) + real Xray core, like v2rayNG.

Pipeline: `TUN -> hev-socks5-tunnel -> SOCKS 127.0.0.1:10808 -> Xray (libv2ray) -> server`

## Get the APK (easiest: GitHub, no Android Studio)
1. Create a GitHub repo and upload everything in this folder (including `.github`).
2. Open **Actions** > **Build APK** (runs on every push, or press "Run workflow").
3. When it's green, download **LordVPN-apk** from Artifacts, unzip, install the `.apk`.

The workflow automatically downloads:
- `libv2ray.aar` (Xray core) from 2dust/AndroidLibXrayLite
- `geoip.dat` / `geosite.dat` from Loyalsoldier/v2ray-rules-dat
- builds `hev-socks5-tunnel` (tun2socks) with the Android NDK

## Build in Android Studio instead
Do the same 3 downloads by hand: put `libv2ray.aar` in `app/libs/`, the two `.dat` files in
`app/src/main/assets/`, and build hev-socks5-tunnel with ndk-build (`-DPKGNAME=com/lord/vpn`)
into `app/src/main/jniLibs/<abi>/libhev-socks5-tunnel.so`. Then Build > Build APK(s).

## Features
- Import: clipboard, paste, subscription URL, manual (vless / vmess / trojan / ss)
- VLESS Reality, TLS, WS, gRPC, HTTPUpgrade, XHTTP, TCP http header
- Real delay test (Xray measureOutboundDelay), sort by ping
- Modes: Proxy only (SOCKS/HTTP), VPN (TUN, with bypass rules), Global (no bypass)
- Routing: bypass Iran (geoip:ir, .ir), bypass LAN, block ads; Mux; Fragment (tlshello); DNS
- Live up/down speed, notification with Disconnect button

## Notes
- Release APK is signed with the debug key so it installs directly. Use your own keystore before publishing.
- Per-app proxy and kill switch toggles are UI only for now (use Android's "Always-on VPN" + "Block connections without VPN" for a kill switch).
