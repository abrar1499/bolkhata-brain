package com.abrar.gupshup

/** Files for a tiny Android app that shows one HTML page (games, tools, calculators...). Built by GitHub Actions. */
object Templates {
    fun webViewApp(appName: String, pkg: String, html: String): List<Pair<String, String>> {
        val safeName = appName.replace("\"", "").replace("<", "").replace("&", "and").ifBlank { "Meri App" }
        return listOf(
            "settings.gradle.kts" to """
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "GupShupApp"
include(":app")
""".trimStart(),
            "build.gradle.kts" to "plugins { id(\"com.android.application\") version \"8.12.0\" apply false }\n",
            "gradle.properties" to "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx3g\n",
            "app/build.gradle.kts" to """
plugins { id("com.android.application") }
android {
    namespace = "$pkg"
    compileSdk = 35
    defaultConfig { applicationId = "$pkg"; minSdk = 24; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
""".trimStart(),
            "app/src/main/AndroidManifest.xml" to """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:label="$safeName" android:icon="@android:drawable/sym_def_app_icon"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true" android:configChanges="orientation|screenSize|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimStart(),
            "app/src/main/java/" + pkg.replace('.', '/') + "/MainActivity.java" to """
package $pkg;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
""".trimStart(),
            "app/src/main/assets/index.html" to html,
            ".github/workflows/build.yml" to """
name: Build APK
on: [push, workflow_dispatch]
permissions:
  contents: write
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
        with: { gradle-version: '8.14.3' }
      - run: gradle :app:assembleDebug --no-daemon
      - env: { GH_TOKEN: ${'$'}{{ github.token }} }
        run: |
          cp app/build/outputs/apk/debug/app-debug.apk app.apk
          gh release delete apk-v1 --yes --cleanup-tag || true
          gh release create apk-v1 app.apk --title "APK" --notes "Gup Shup se bani app"
""".trimStart(),
        )
    }
}
