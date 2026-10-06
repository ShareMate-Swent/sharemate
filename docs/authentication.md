# Firebase authentication foundation (US-1)

`AuthRepository` exposes email/password sign-up, login, sign-out and session
updates. `AuthSession` holds only the Firebase identity; User/Household models
remain separate. Firebase owns persistence and authentication listeners.
No Firestore profile or household is created by this repository.

Enable **Authentication → Sign-in method → Email/Password** in the Firebase
console for **sharemate-swent** before using real accounts. Never commit local
credentials or `local.properties`. Shared preferences are excluded from backup
and device transfer to prevent copying Firebase session credentials.

Firebase BoM 33.8.0 selects Auth 23.1.0, compatible with Kotlin 1.9. Newer Auth
artifacts require Kotlin 2.1/2.3; Auth 23.2.1 and 24.0.0 also have a documented
session-persistence regression. See the [Firebase Android release notes](https://firebase.google.com/support/release-notes/android).

For local tests, run `firebase emulators:start --only auth --project demo-sharemate`
and `adb reverse tcp:9099 tcp:9099`. Tests use a separate demo Firebase app and
`127.0.0.1:9099`; production never calls `useEmulator`. Debug builds permit HTTP
only for localhost. Use stable Android 14/API 34 for instrumented verification.

## Welcome and application integration

The welcome screen follows the supplied Figma design, with the original fridge
image, a local light theme, and responsive scrolling. Login and account creation
open the existing email/password forms. Back clears passwords and errors;
only form visibility is saved across recreation, never credentials. Restored
sessions bypass welcome; signing out returns to it.

After merging navigation PR #39, place its navigation root inside `AuthGate` in
`MainActivity`, replacing the greeting. Navigation tests must first supply a
fake session or use the local Auth emulator, never the live Firebase project.
