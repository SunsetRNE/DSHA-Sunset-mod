# DSHA 0.2.2-rc.5-20261119.0600-dsh0.2.2-rc.5 / build371

- The app and bundled DSH are both at 0.2.2-rc.5. The local Standard build uses package `dsh.android.client` and the existing E7E3 signing certificate. It supports arm64 devices running Android 11 or later.
- Fixed terminal session creation when the default terminal arguments are empty.
- When you request startup during an environment task, the app waits for the task to finish and then continues. Startup repeats less environment preparation and plugin registration work.
- Reduced redundant background drawing while scrolling the native interface and duplicate processing while scrolling web pages.
