# Hyper Stream branding

- `hyperstream-monogram.svg`: the HS monogram, white on transparent, on a 512 grid. The H's crossbar runs into the S's spine.
- `hyperstream-icon.svg`: the app icon, the monogram on the brand blue `#0A47F8` with rounded corners.
- `../desktopApp/src/desktop-icons/icon.svg`: the same icon on Apple's grid (an 824px tile in a 1024px canvas) for the Mac.

The theme's accent is the same blue (`HyperStreamTheme.kt` in appCore); other accents can still be picked in Settings.

## Making the icon files

Images are rendered with Skia by `BrandRenderTest` (appCore/src/jvmTest), which only runs when asked:

```bash
OUT=/tmp/hs-icons
JOBS=""
for s in 16 32 64 128 256 512 1024; do JOBS="$JOBS$PWD/desktopApp/src/desktop-icons/icon.svg|$OUT/mac_$s.png|$s;"; done
JOBS="$JOBS$PWD/branding/hyperstream-icon.svg|$OUT/full_256.png|256;$PWD/branding/hyperstream-icon.svg|$OUT/full_512.png|512"
CS_BRAND_JOBS="$JOBS" CS_BRAND_BANNER="$PWD/branding/hyperstream-monogram.svg|$OUT/banner.png" ./gradlew :appCore:jvmTest --tests '*BrandRender*' --rerun
```

Then:

- Mac: copy `mac_*.png` into a `HyperStream.iconset` folder with Apple's names (`icon_16x16.png`, `icon_16x16@2x.png` and so on up to `icon_512x512@2x.png`) and run `iconutil -c icns HyperStream.iconset -o desktopApp/src/desktop-icons/icon-mac.icns`. `mac_512.png` is also `desktopApp/src/jvmMain/resources/app-icon.png`.
- Windows: `ffmpeg -i full_256.png desktopApp/src/desktop-icons/icon-windows.ico`.
- Linux: `full_512.png` is `desktopApp/src/desktop-icons/icon-linux.png`.
- In-app logo: `full_256.png` is `appCore/src/commonMain/composeResources/drawable/app_icon.png`.
- Android: the launcher icon is a vector (`androidApp/src/main/res/drawable/ic_launcher_foreground.xml`, same paths as the SVG) on the blue background colour; `banner.png` is the TV banner, `res/drawable-xhdpi/ic_banner.png`.
