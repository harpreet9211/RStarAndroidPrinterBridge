# RStar Android Printer Bridge

This is a first-build Android companion app for RStar POS. It starts a local HTTP server on
`127.0.0.1:3001` when the app opens and sends ESC/POS byte arrays to a Wi-Fi/LAN printer
using raw TCP.

## Compatibility target

- Minimum SDK: Android 5.0 (API 21)
- Compile/target SDK: Android 15 (API 35)
- Build: GitHub Actions (no local Android Studio required)

This is a compatibility target, not a guarantee for every tablet. Test on each Android
version and manufacturer, especially background-service behavior and Chrome local-network
permissions.

## Build APK without Android Studio

1. Create a GitHub repository and upload this project.
2. Open the repository's **Actions** tab.
3. Select **Build RStar Printer Bridge APK**.
4. Select **Run workflow**.
5. When complete, open the workflow run and download the
   `RStar-Printer-Bridge-debug` artifact.
6. Extract the ZIP; it contains `app-debug.apk`.
7. Transfer and install the APK on the Android tablet. Allow installation from that source.

## App behavior

- Opening the app starts a foreground service.
- The local server listens only on `127.0.0.1:3001`.
- Stop the bridge using Android Settings > Apps > RStar Printer Bridge > Force stop,
  or use the app's Stop button. Reopening the app starts it again.
- A persistent notification indicates that the bridge is running.

## API

`GET http://127.0.0.1:3001/api/bridge/status`

`POST http://127.0.0.1:3001/api/print`

JSON body:
```json
{
  "ip": "192.168.1.150",
  "port": 9100,
  "bytes": [27, 64, 27, 97, 1]
}
```

Allowed browser origins are configured in `LocalHttpServer.kt`:
- `https://rstar.rangerlive.com`
- `http://localhost:4200`
- `http://127.0.0.1:4200`

## Important limitations / security

- The bridge accepts dynamic IPv4 printer addresses and currently does not restrict the target
  printer list. Keep it bound to loopback; do not expose it on the Wi-Fi interface.
- CORS is not authentication. This is intended for the RStar POS website on the same tablet.
- Chrome may require local-network/loopback permission for the website.
- Android can still stop a service under manufacturer-specific battery policies. The app uses
  a foreground service, but long-duration reliability must be tested on the actual tablet.
- The response confirms that bytes were sent to the printer socket, not that paper physically
  printed.
- The current project is an initial debug APK build. Before broad deployment, test printing,
  Chrome permission prompts, Android 13+ notification behavior, and Android 14+ foreground
  service requirements on target devices.
