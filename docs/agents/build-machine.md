# Build-machine setup

Use a Temurin JDK 17 from [Adoptium](https://adoptium.net/temurin/releases/?version=17) and the repo's Gradle wrapper.
Android Studio's bundled Java runtime may be JBR 25, which Gradle 8.11.1 cannot run on. Point `JAVA_HOME` at JDK 17
even when Android Studio installed the SDK.

Install the [Android command-line tools](https://developer.android.com/studio#command-line-tools-only) in the
SDK's `cmdline-tools/latest/` directory, with `sdkmanager` under `latest/bin/`. The app compiles against
`platforms;android-35`. The Android Gradle plugin uses `build-tools;34.0.0`, including its `aapt2` for APK
verification. The `platform-tools` package provides `adb` for [on-device checks](emulator.md).

## Windows

Install Git for Windows with its default checkout settings. Use a short worktree path such as `C:\cw\<ticket>`
to leave room for the generated paths in Android builds.

Create `$HOME\.config\celastrina\env.ps1` with your JDK and SDK paths:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\celastrina\jdk17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
```

Load the script in PowerShell on each new terminal, install the SDK packages once, then verify from the checkout root:

```powershell
. "$HOME\.config\celastrina\env.ps1"
$sdkmanager = "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat"
& $sdkmanager --licenses
& $sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0"
java -version
.\gradlew.bat :core:test :app:verifyDebugApk
```

## macOS

Install the JDK for your CPU architecture and select it with `/usr/libexec/java_home -v 17`. The SDK location
below is Android Studio's default. Set `ANDROID_HOME` to the SDK root you installed.

Create `~/.config/celastrina/env.sh`:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Load the script on each new terminal and follow [SDK installation and verification](#sdk-installation-and-verification)
below.

## Linux

Install the JDK and SDK in per-user directories or use existing system installs. Create
`~/.config/celastrina/env.sh` with the actual paths:

```sh
export JAVA_HOME="$HOME/.local/opt/jdk17"
export ANDROID_HOME="$HOME/.local/opt/android-sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Load the script on each new terminal and follow [SDK installation and verification](#sdk-installation-and-verification)
below.

### SDK installation and verification

On macOS and Linux, install the SDK packages once, then verify from the checkout root:

```sh
. ~/.config/celastrina/env.sh
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;34.0.0"
java -version
./gradlew :core:test :app:verifyDebugApk
```

On all three platforms, Java should report version 17 and both Gradle tasks should pass.

## User-level build settings

Use `gradle.properties` in your Gradle user home, normally `~/.gradle/` on macOS/Linux or
`%USERPROFILE%\.gradle\` on Windows. On a machine with more memory than the repo's 4 GB build box, you can
override the default 1.8 GB heap and 512 MB metaspace in that file. For example:

```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8
```

Put `TMDB_API_KEY=<your key>` in the same user-level file or set the environment variable from your local secret
store. Keep the key out of the checkout. Debug builds and the host checks work without a key. A tagged release
build needs the key and the [release signing inputs](../release-keystore.md).
