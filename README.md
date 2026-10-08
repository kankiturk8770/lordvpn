<p align="center"><img src="docs/logo.png" width="120" alt="Lord V2"/></p>
<h1 align="center">LORD V2</h1>
<p align="center">Premium Android VPN client · V2Ray/Xray config management · Surfshark‑inspired UX</p>

---

## 🇮🇷 راهنمای سریع (فارسی)

### ساخت APK با GitHub (بدون Android Studio)
1. یک ریپازیتوری جدید در GitHub بسازید (مثلاً `LordV2`).
2. محتوای این فایل زیپ را **از حالت فشرده خارج کنید** و همه‌ی فایل‌ها (به‌همراه پوشه‌ی مخفی `.github`) را در ریپو آپلود/Push کنید.
   - اگر از وب‌سایت GitHub آپلود می‌کنید: «Add file → Upload files» و کل پوشه‌ها را بکشید و رها کنید. مطمئن شوید پوشه‌ی `.github/workflows` هم آپلود شده باشد.
3. به تب **Actions** بروید. Workflow با نام **Build Lord V2 APK** خودکار اجرا می‌شود (یا دکمه‌ی *Run workflow* را بزنید).
4. بعد از حدود ۵ تا ۸ دقیقه، داخل همان اجرای Workflow در بخش **Artifacts** فایل `LordV2-apk` را دانلود کنید. داخلش دو فایل هست:
   - `app-debug.apk` ← برای نصب و تست
   - `app-release.apk` ← نسخه‌ی Release (فعلاً با کلید Debug امضا شده تا مستقیم نصب شود)

### هسته‌ی اتصال (Xray)
Workflow به‌صورت خودکار فایل `libv2ray.aar` (هسته‌ی رسمی Xray برای اندروید از پروژه‌ی AndroidLibXrayLite) را دانلود و داخل `app/libs` می‌گذارد. اگر به هر دلیل دانلود نشود، برنامه باز هم ساخته می‌شود ولی هنگام اتصال پیام واضح «The Xray engine is not bundled» نشان می‌دهد. می‌توانید فایل را دستی در `app/libs/libv2ray.aar` قرار دهید.

### حالت‌های تونل (Settings › Advanced › Tunnel Mode)
- **Proxy (پیش‌فرض):** پروکسی HTTP سیستمی از طریق VPN (اندروید ۱۰ به بالا). مرورگرها و اکثر اپ‌ها از آن عبور می‌کنند. پایدارترین حالت.
- **Full (TUN) – آزمایشی:** همه‌ی ترافیک دستگاه وارد تونل می‌شود؛ نیاز به نسخه‌ای از Xray دارد که TUN inbound را پشتیبانی کند.

---

## Features

| Area | What's inside |
|---|---|
| **Home** | Dotted world map (Natural Earth based, cached rendering), server dots, animated arc from your location to the server, pulse on connect, NOT CONNECTED / CONNECTING... / CONNECTED status, compact CONNECT / DISCONNECT pill, cards for IP · Ping · Time · Download · Upload · Config |
| **Configurations** | Modern cards (flag, country, name, protocol, transport, ping, status, last used), active marker, Connect + More menu (Edit · Duplicate · Rename · Test Ping · Export · Delete), search, sort by Name / Ping / Recently Used / Country, Test All, lazy list |
| **Add Configuration** | Clipboard · File · URL · QR scanner · Manual editor · Subscription URL. Share‑to‑app and `vless://` / `vmess://` / `trojan://` / `ss://` deep links also import |
| **Protocols** | VLESS (incl. REALITY, Vision flow), VMess, Trojan, Shadowsocks (incl. 2022), SOCKS · transports TCP / WS / gRPC / HTTP2 / HTTPUpgrade / XHTTP · TLS / REALITY. Modular parser + config builder |
| **Subscriptions** | Add / edit / delete, update now, update all, auto update + interval, last updated, config count, enable/disable, clear error messages with Retry |
| **Servers** | Grouped by country (Germany, US, UK, France, Netherlands, Singapore, Japan first), collapsible, FAST badge, latency‑based load, Auto Select (tests & connects to the fastest) |
| **Ping** | TCP handshake test per config, Test All (8 parallel), Excellent / Good / Average / Poor colors, real HTTP 204 latency once connected |
| **Statistics** | Live download/upload chart (60 s), connection time, downloaded, uploaded, current speed, average ping, total connections, all‑time totals |
| **Logs** | Colored, timestamped, sanitized (UUIDs / links / keys are masked), Clear Logs |
| **Settings** | General · Connection · Configurations · Appearance · Advanced, exactly as specified, with Kill Switch, DNS bottom sheet (Automatic / System / Custom), Routing (Global / Rule / Direct), IPv6, timeout, default config, backup import/export, Dark / Light / AMOLED, animation toggle, map style, debug mode, network info, reset |
| **Notification** | "Connected · Germany • 34 ms" with live speed and a **Disconnect** action |
| **Onboarding** | Welcome to Lord V2 → Your Configs. Your Connection. Your Control. → Fast, Private & Simple → Get Started |
| **Errors** | Connection Failed (Retry / Change Config), Subscription Update Failed (Retry), Invalid Configuration |

## Security
- Configs and subscriptions are stored **AES‑256‑GCM encrypted** with a key in the **Android Keystore**.
- `allowBackup=false`, secrets are masked in the UI (reveal on demand), logs are sanitized.
- Export always shows a warning; copied links are flagged as sensitive on Android 13+.

## Performance
- Map land layer is pre‑computed and drawn once with `drawWithCache` + `drawPoints`; only a tiny overlay animates, and infinite animations run **only** while connecting/connected.
- Animations can be disabled globally (Settings › Appearance › Animation).
- All lists are `LazyColumn` with stable keys.

## Tech stack
Kotlin 2.0 · Jetpack Compose (Material 3) · Navigation Compose · Coroutines/Flow · VpnService · Xray core via `libv2ray.aar` (reflection bridge, pluggable) · ZXing scanner.

## Project structure
```
app/src/main/java/com/lordv2/app
├── core/CoreBridge.kt          # pluggable Xray engine bridge
├── data/                       # models, link parser, Xray config builder, secure store, repo, ping
├── vpn/                        # VpnService, notification, auto-connect, boot receiver, state
└── ui/                         # theme, components (map, chart, logo), screens, navigation
```

## Build locally
Open the folder in Android Studio (Koala or newer) and press Run, or with Gradle 8.9+ installed:
```
gradle :app:assembleDebug
```
Before publishing on a store, replace the debug signing config in `app/build.gradle.kts` with your own keystore.
