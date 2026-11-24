# Reproducible debug build

This note is for a build on this machine. It is not an F-Droid submission.

SDK path: `android/local.properties` `sdk.dir`.

```
android/gradlew :app:assembleDebug -p android
```

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. After a build, checksum it with:

```
shasum -a 256 android/app/build/outputs/apk/debug/app-debug.apk
```

Release signing is not configured here, and nothing from this build is published.
