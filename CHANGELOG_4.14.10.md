# Travian Manager 4.14.10

## Background Resource Builder
- Service bootstraps/recoveries from `onCreate()`/`START_STICKY` without requiring MainActivity to be open.
- Every scheduled cycle waits 1 minute after the cycle countdown reaches zero, then runs a headless REFRESH VILLAGE in the service WebView.
- Automatic refresh reads only villages with `IsChecklist=true` from `village_data_json`.
- Refresh updates `LinkResource` and `MinLvl` in Village Data before Resource Builder starts.
- Resource Builder uses Village Data as its single source of truth; saved target/link JSON is no longer required for execution.
- Farm List and Resource Builder are synchronized: Resource Builder starts only after both Farm List and REFRESH VILLAGE are complete.
- Refresh works with MainActivity closed because it no longer calls the Activity to perform the background refresh.
- Refresh failures leave that village's resource target empty so stale targets are not reused.
- Cycle watchdog was extended to 15 minutes to allow refresh + builder work on multiple villages.

## Credentials
- Added `CredentialDatabase.kt` using SQLite.
- Server, username and password are stored in the credential database; password is encrypted with Android Keystore AES-GCM.
- Existing legacy `password_secure` data is migrated once when the app starts, then removed from SharedPreferences.
- Service recovery reads credentials from the database.

## Android background service
- Foreground Service uses `specialUse` so the service is not subject to the Android 15 `dataSync` six-hour daily limit.
- Added `FOREGROUND_SERVICE_SPECIAL_USE` permission and special-use subtype declaration.
