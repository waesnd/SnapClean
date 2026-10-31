# SnapClean
An offline Android workspace for reviewing historical Snapchat friend-request exports.

## v2.3 beta
- Transactional SQLite storage with one-time migration from 2.2 SharedPreferences.
- JSON backup and restore using Android document picker. Backups contain usernames and are not encrypted.
- Date and username sorting, multi-select and bulk reviewed/skipped actions.
- "Pending" renamed to "Unreviewed" to avoid implying Snapchat confirms live status.
- No account login, automated friend removal, or private Snapchat API usage.

## v2.0 beta
- English and Turkish UI; language toggle and device-language default.
- Dark interface, progress dashboard, search, and review filters.
- Import Snapchat `friends.html` records; merge duplicate usernames.
- Tap a username to open its public Snapchat profile URL; copy as fallback.
- Manually mark records reviewed or skipped; persist locally between launches.
- Clear local records and review state with confirmation.
- No Snapchat credentials, no INTERNET permission, no server upload.

## Important limitations
- Exported *historical* sent requests are not proof that a request is still pending.
- Snapchat profile links may open in a browser or fail to resolve in the app.
- No automatic cancellation, screen reading, or account automation.
- The HTML parser recognizes a limited set of heading labels and table structures; real exports in other languages/layouts may not import correctly.
- Local data is stored in app-private SharedPreferences, not an encrypted database. Large imports may be slow; this beta is not yet validated on a real export.
- Not affiliated with, endorsed by, or sponsored by Snap Inc. Snapchat is a trademark of Snap Inc.

## Build
GitHub Actions: Actions → Build Android APK → Run workflow → download SnapClean-debug artifact (ZIP containing app-debug.apk).

## Privacy
All imported data stays on-device. Do not commit private Snapchat export files to this public repository.

## Roadmap
Test import against redacted sample exports, migrate records to Room with background import, accessibility improvements, additional localizations, privacy policy, accessibility QA, release signing and Play Store readiness.
