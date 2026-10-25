# Console UI asset attribution

- IBM Plex Mono Regular, Latin WOFF2 (10,052 bytes), from Google Fonts:
  https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400&display=swap
  Copyright IBM Corp.; SIL Open Font License, see IBM-Plex-Mono-OFL.txt.
  The exact WOFF2 bytes are embedded in console_ui.py and loaded with the
  standard FontFace ArrayBuffer API. The existing app.js route serves them;
  no external font requests, new routes or CSP changes are needed. System
  monospace is the fallback if the font API is unavailable.
- Lucide SVG icons from https://github.com/lucide-icons/lucide/tree/main/icons
  are embedded as trusted markup in console_ui.py. ISC license and Feather
  attribution are preserved in Lucide-LICENSE.txt.

Assets retrieved 2026-10-26. No package manager or CDN is used at runtime.
