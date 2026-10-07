# Firebase authentication foundation (US-1)

`AuthRepository` exposes email/password sign-up, login, sign-out and session
updates. `AuthSession` holds only the Firebase identity; User/Household models
remain separate. Firebase owns persistence and authentication listeners.
No Firestore profile or household is created by this repository.

Enable **Authentication → Sign-in method → Email/Password** in the Firebase
console for **sharemate-swent** before using real accounts. Never commit local
credentials or `local.properties`. Shared preferences are excluded from backup
and device transfer to prevent copying Firebase session credentials.

Firebase BoM 34.19.0 and Kotlin 1.9.23 are preserved from main. The catalogue
strictly selects Auth 23.1.0 only: Auth 24.2.0 selected by the BoM failed
compilation because its Kotlin metadata is 2.3.0. Compilation succeeded with
the Auth-only constraint, without a global dependency or toolchain upgrade.

For local tests, run `firebase emulators:start --only auth --project demo-sharemate`
and `adb reverse tcp:9099 tcp:9099`. Tests use a separate demo Firebase app and
`127.0.0.1:9099`; production never calls `useEmulator`. Debug builds permit HTTP
only for localhost. Use stable Android 14/API 34 for instrumented verification.
