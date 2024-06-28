# PCAPdroid mitm addon

A [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) addon which uses [mitmproxy](https://mitmproxy.org) to decrypt the TLS/SSL connections and show the decrypted data in the app.

The addon uses the open source framework [chaquopy](https://chaquo.com/chaquopy) to bundle and run python modules. The native python modules are pre-built and installed from the chaquopy [pip repository](https://chaquo.com/pypi-7.0).

# Build

The following build instructions work on a clean `Ubuntu 24.04 LTS` at the moment, but may get updated.
Build on Windows is not currently supported.

1. Install Java 17 and git

```
sudo apt install openjdk-17-jdk git

# should print "openjdk 17.x"
java --version
```

2. Install python 3.10, which is required to correctly build with Chaquopy

```
sudo add-apt-repository ppa:deadsnakes/ppa
sudo apt install python3.10 python3.10-distutils

# should print "Python 3.10.x"
python3.10 --version
```

3. Clone the repo

```
git clone https://github.com/emanuele-f/PCAPdroid-mitm
cd PCAPdroid-mitm
git submodule update --init
```

4. Install the Android SDK. These instructions assume you will work from the CLI, however you can do the same via Android Studio, which will make installing and managing SDK versions easier.

```
# 1. Grab the latest "Command line tools only" for linux from https://developer.android.com/studio,
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip

# 2. Set up the environment
export ANDROID_SDK_ROOT=~/Android
alias sdkmanager="cmdline-tools/bin/sdkmanager --sdk_root=$ANDROID_SDK_ROOT"
sdkmanager --update

# 3. Install the SDK
# android-34 corresponds to targetSdk in app/build.gradle
sdkmanager "platforms;android-34" "extras;google;m2repository" "extras;android;m2repository"
```

5. Create keystore for signing

```
keytool -genkey -alias key0 -keyalg RSA -keystore keystore -storepass android -keypass android -dname "CN=Unknown, OU=Unknown, O=Unknown, C=Unknown"
```

6. Build release

```
./gradlew assembleRelease

# The signed apks should be located under `./app/build/outputs/apk`
# find . -name "*.apk"
```
