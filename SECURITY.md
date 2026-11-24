# Security

Deft apps run in per-app WebView origins. Report a vulnerability by opening a private issue or emailing the maintainer listed in MAINTAINERS.md. Please include a repro and the WebView version (`adb shell dumpsys package com.google.android.webview`).

Do not open a public issue for an unfixed exploit.

What is in scope: the JS bridge, CSP, package verification, path rules, and grant prompts. What is out of scope: a user granting a capability and then being surprised by what that capability can read.

There is no public bug bounty. Fixes land in this repo; a note goes in docs/spec/DECISIONS.md when a security decision changes.
