# Setu VPN

[Download the signed Android APK](https://github.com/gautamagarwal529-collab/setu-vpn/releases)

Setu VPN is an Android OpenVPN client with a country-based directory of public relays, global and per-country Quick Connect, and standalone OpenVPN profile imports. Pull down to refresh the directory. Listed servers can go offline or become slow at any time.

## Build from source

Install OpenJDK 21, Gradle 9.7.1, SWIG, Android SDK Platform 37, Android NDK 30.0.14904198, and Android SDK CMake. Set `ANDROID_HOME` to the Android SDK installation. From the repository root, run the release task.

```sh
gradle :main:assembleUiOvpn23Release
```

The unsigned APK is written to `main/build/outputs/apk/uiOvpn23/release/`. The project compiles the Java, Kotlin, and native OpenVPN components together. The Android application ID is `com.paperline.vpn`. The release version is 1.19, with version code 20.

## Source and licensing

The app code is distributed under the GNU GPL version 2. The full license is in [LICENSE](LICENSE). Upstream OpenVPN for Android notices and linking exceptions are in [doc/LICENSE.txt](doc/LICENSE.txt). Vendored dependencies retain their respective license files. The Setu bridge artwork is included under GPL version 2. Inter is distributed under the SIL Open Font License in `main/src/main/assets/fonts/Inter-OFL.txt`. Flag glyphs come from the device's emoji font and receive app-rendered lighting.

The vendored OpenVPN for Android source comes from the F-Droid source archive for 0.7.69, version code 225. Its F-Droid build metadata references upstream revision `f669267e2547fddca04d0bc699739137085eb633`. The app includes public relay configurations and directory data from VPN Gate and the VPN Gate list mirror. Provider links in the country list can open external sites.

[OpenVPN for Android source](https://github.com/schwabe/ics-openvpn/tree/f669267e2547fddca04d0bc699739137085eb633) · [F-Droid source archive](https://f-droid.org/repo/de.blinkt.openvpn_225_src.tar.gz) · [VPN Gate](https://www.vpngate.net/en/) · [VPN Gate list mirror](https://github.com/GeorgeXie2333/vpngate-list-mirror)

## Privacy

Setu VPN contacts the directory hosts to refresh listings and retrieve public relay profiles. These requests and direct relay checks expose the device's network address to the servers they contact. When a VPN connects, the chosen relay handles the tunneled traffic. VPN Gate records connection logs, and volunteer relay operators can record packet headers. The policies of imported profiles and external providers apply to their use. Imported profiles stay in Android's private app storage. The app uses Android's VPN permission to start a tunnel.

[VPN Gate anti-abuse policy](https://www.vpngate.net/en/about_abuse.aspx)

## F-Droid

The listing text and artwork are under `fastlane/metadata/android/en-US`. The draft recipe in `submission/com.paperline.vpn.yml.in` needs the public source and issue tracker URLs. The official catalog requires maintainer review and a successful build.

[F-Droid submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)

## Signed GitHub release

The downloadable APK uses the same Setu app source and the pinned F-Droid OpenVPN for Android 0.7.69 engine. The release packaging script is in `release-build/build.sh`. It takes Android SDK Platform 35's `android.jar`, Android Build Tools 35.0.0, ECJ 3.39.0, the verified `de.blinkt.openvpn_225.apk`, the bundled directory JSON, and an output APK path. The script checks the engine APK's SHA-256 and defaults to an unsigned build for local use.

```sh
./release-build/build.sh /path/to/android.jar /path/to/build-tools/35.0.0 /path/to/ecj.jar \
    /path/to/de.blinkt.openvpn_225.apk main/src/main/assets/paperline-directory.json SetuVPN-1.19.apk
```

To sign with your own keystore, set `PAPERLINE_KEYSTORE`, `PAPERLINE_KEYSTORE_PASSWORD`, and `PAPERLINE_KEY_ALIAS`. The public release uses the original Setu VPN signing key to support updates over 1.18.

[OpenVPN for Android builds](https://f-droid.org/packages/de.blinkt.openvpn/)
