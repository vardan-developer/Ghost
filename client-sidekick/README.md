# ghosta — accessibility display helper

Small companion app for owner-authorized device management. It exposes a
generic accessibility API to the main ghostd app over loopback TCP, so the
operator can inspect the UI and decide what to tap. It is not a hidden
surveillance component and does not implement detection evasion.

ghostd installs ghosta from its bundled asset. The accessibility service must
be enabled by the device owner in Android Settings; Android then shows its
normal accessibility indicator and rebinds the service after boot.

## Local link

- TCP `127.0.0.1:7777`, newline-delimited JSON, one response per request.
- The first line must carry the shared local-link authentication field; the
  static value is intentionally omitted from this document. It is not a
  network credential, but it should still be rotated in both apps together.
- `INTERNET` is declared only because Android requires that permission even to
  listen on loopback. ghosta makes no outbound network connections.
- There is no foreground service and no notification; the system binds the
  accessibility service.

### Commands

```text
{"cmd":"status"}                              -> armed flag, confirm labels
{"cmd":"dump"}                                -> full node tree (nested)
{"cmd":"find","text":"Allow"}                 -> substring match on text/desc
{"cmd":"find","id":"pin_entry"}               -> view resource id match
{"cmd":"tap","id":"root/0/3"}                 -> node path
{"cmd":"tap","x":540,"y":1800}                -> coordinates
{"cmd":"longclick","id":...} | {"cmd":"longclick","x":..,"y":..}
{"cmd":"type","text":"hello","id":"root/1"}   -> id optional; SET_TEXT,
                                                 key-by-key fallback
{"cmd":"key","code":66}                       -> KEYCODE_*
{"cmd":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"duration":300}
{"cmd":"unlock","pin":"1234"}                 -> PIN entry + submit
{"cmd":"screenshot","format":"png","quality":80} -> accessibility screenshot
{"cmd":"arm"} / {"cmd":"disarm"}              -> installer/permission consent
{"cmd":"arm","expect":"projection","ttl_ms":15000}
                                                 -> short-lived projection consent
{"cmd":"ping"}
```

Responses are `{"ok":true,...}` or `{"ok":false,"error":"..."}`. A node id is
an index path from the root (`"root"`, `"root/0"`, `"root/0/3"`) and is valid
only until the screen changes; re-run `find` after a transition.

## Consent automation

- Only exact recognized system packages and windows carrying a
  MediaProjection, PackageInstaller or permission marker are eligible. Generic
  matching in arbitrary apps is forbidden.
- Nothing is tapped while the keyguard is locked or its state is unknown.
- MediaProjection consent is confirmed only after ghostd arms a short-TTL
  projection expectation immediately before its own capture. It is not
  always-on and never applies to another app's capture sheet.
- Installer and permission consent remains disabled until explicit `arm`;
  default is off.
- Attempts, retries and tap frequency are bounded. Nodes are preferred over
  coordinates, and late or duplicate windows are ignored.
- Android's camera/microphone green dot and screen-sharing indicator are OS
  behavior. ghosta does not hide them. Accessibility screenshots are the
  preferred still-image path because they need no MediaProjection consent.

## Build and test

```bash
cd client-sidekick
gradle test
gradle assembleRelease
```

The APK is staged for OTA with `server/ghostctl.py apks add ghosta <path>`.
