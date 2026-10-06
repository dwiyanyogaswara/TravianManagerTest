# No Live Bot

This build has no visible Live Bot page or Travian WebView in the Activity UI.

- `MainActivity` contains only controls, status, village selection, capacity, and logs.
- The Activity WebView compatibility object is `gone` and has zero visible size.
- All Travian DOM reading and automation are performed by the private WebView owned by `FarmAutomationService`.
- The service WebView uses `loadUrl()` and `evaluateJavascript()` against the real Travian pages.

The service must remain a foreground service for background operation.
