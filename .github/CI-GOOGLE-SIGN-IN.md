<!-- Co-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com> -->

# Google Sign-In for the CI APK

The APK uploaded from `main` is signed with a stable CI keystore. The CI
workflow expects these repository secrets:

- `ANDROID_KEYSTORE_BASE64`: the keystore file encoded as one Base64 line
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password
- `ANDROID_KEY_ALIAS`: the key alias
- `ANDROID_KEY_PASSWORD`: the key password

Create the keystore locally and keep it in a secure backup. Do not commit the
keystore or share its passwords:

```sh
keytool -genkeypair -v \
  -keystore sharemate-ci-debug.p12 \
  -storetype PKCS12 \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias sharemate-ci
```

For PKCS12, use the same password when prompted for the keystore and key.
Get the certificate fingerprints with:

```sh
keytool -list -v \
  -keystore sharemate-ci-debug.p12 \
  -storetype PKCS12 \
  -alias sharemate-ci
```

Add the SHA-1 fingerprint (and preferably SHA-256) in Firebase Console under
**Project settings → Your apps → Android app (`com.android.sharemate`)**.
Confirm Google is enabled under **Authentication → Sign-in method**, then
download the updated `google-services.json` and replace
`app/google-services.json` in the repository.

Encode the keystore as a single Base64 line:

```sh
base64 -w 0 sharemate-ci-debug.p12
```

In the GitHub repository, open **Settings → Secrets and variables → Actions**
and create the four secrets listed above. Store the Base64 output in
`ANDROID_KEYSTORE_BASE64`; enter the remaining values as prompted during
keystore creation. Once these secrets are present, a push to `main` builds the
APK with this certificate and uploads it as `sharemate-m1-apk`.

Local builds and pull requests continue to use Android's standard debug key.
