# 依存ライブラリ・ツールチェーン（2026-11-26 更新）

方針：オーナー指示「ライブラリは最新を取り入れる」。**安定版のみ**（alpha/beta/rc を除く）、**公開から14日以上**経ったものだけを採用。各更新は個別コミット（CI 失敗時に二分探索できるように）。
確認方法：dl.google.com/android/maven2・repo1.maven.org・jitpack の maven-metadata、services.gradle.org、sdkmanager --list、`git ls-remote --tags`。
ローカル検証：JDK 21＋Android SDK 36 で `:app:compileReleaseJavaWithJavac`（Java/リソース）と `:app:externalNativeBuildRelease`（ネイティブ）。APK 全体は CI で確認。

## ツールチェーン

| 項目 | 旧 | 新 | 備考 |
|---|---|---|---|
| Gradle wrapper | 8.12 | **9.9.1** | 9.9.2 は公開7日で見送り |
| Android Gradle Plugin | 8.2.2 | **9.5.1** | 9.5.2/9.5.3 は14日未満。DSL: `packagingOptions`→`packaging`、`lintOptions`→`lint`、`targetSdkVersion`/`minSdkVersion`→`targetSdk`/`minSdk`、`buildDir`→`layout.buildDirectory`、`task clean`→`tasks.register` |
| JDK（CI） | 17 | **21** | Gradle 9 / AGP 9 は 17 以上。ローカル検証も 21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 36 | 36 / 36 / 36 | minSdk は D5（Android 16+）のため据え置き |
| build-tools（CI） | 35.0.0 | **36.0.0** | |
| NDK | 28.0.12674087（**rc2**） | **30.0.16248370**（r30。当初 28.2.13676358、vendored 修正後に r30 へ） | r30（30.0.16248370）は vendored sentencepiece が新 clang でコンパイル不可（`constexpr variable 'kAnyType' must be initialized by a constant expression`） |
| CMake | 3.22.1 | **4.1.2**（当初 3.31.6、vendored 修正後に 4.x へ） | 4.x は vendored の `cmake_minimum_required(<3.5)` を拒否。`-DCMAKE_POLICY_VERSION_MINIMUM=3.5` で通しても ssplit-cpp の PCRE2 ExternalProject のパスが空になり失敗 |

## ライブラリ

| ライブラリ | 旧 | 新 | 備考 |
|---|---|---|---|
| com.google.android.material:material | 1.9.0 | **1.14.0** | |
| androidx.recyclerview | 1.0.0 | **1.4.0** | |
| androidx.constraintlayout | 1.1.3 | **2.2.2** | |
| androidx.preference | 1.1.0-alpha02 | **1.2.1** | alpha から安定版へ |
| androidx.core:core-splashscreen | 1.0.1 | **1.2.0** | |
| androidx.work:work-runtime | 2.7.1 | **2.12.0** | |
| androidx.exifinterface | 1.3.7 | **1.4.2** | 1.5.0 は beta |
| androidx.room（runtime/compiler/rxjava2/guava/testing） | 2.1.0 | **2.8.5** | Java の annotationProcessor のまま動作 |
| com.google.guava:guava | （Room 2.1 経由の推移的依存） | **33.8.0-android** を明示 | Room 2.8 の room-guava は Guava を持ち込まない。`TensorUtils` が Guava primitives を使用 |
| com.google.code.findbugs:jsr305 | （推移的） | **3.0.2** を明示 | `javax.annotation.Nullable/Nonnull` 用 |
| androidx.cardview | 1.0.0 | 1.0.0 | 最新 |
| androidx.lifecycle:lifecycle-extensions | 2.2.0 | 2.2.0 | 廃止済みの最終版。置換はコード変更を伴うため別作業 |
| com.microsoft.onnxruntime:onnxruntime-android | 1.30.0 | **1.31.1** | 1.32.0 は公開13日で見送り。`RunOptions.setTerminate`・`run(Map, RunOptions)` は存続（javac 通過）。arm64 .so の LOAD は 0x4000 整列（16KB ページ） |
| ~~onnxruntime-extensions-android~~ | 0.13.0 | **撤去** | 使っていたのは Whisper_detokenizer.onnx の BpeDecoder だけ。ネイティブライブラリが 16KB 未整列（新版なし）のため Java の `WhisperDetokenizer` に置き換え（同じ出力を確認） |
| com.google.code.gson:gson | 2.14.0 | **2.15.0** | |
| com.google.protobuf:protobuf-javalite | 4.33.5 | **4.36.2** | 4.37.0 は RC |
| org.bouncycastle:bcprov-jdk15to18 | 1.83 | **1.87** | |
| ~~ai.djl.android:core / tokenizer-native、ai.djl.huggingface:tokenizers~~ | 0.33.0 | **撤去** | 使っていたのは HY-MT の tokenizer.json だけ。`libdjl_tokenizer.so` が 16KB 未整列（Android 版は 0.33.0 止まり）のため Java の `HyTokenizer` に置き換え（同じ ID を確認） |
| commons-io:commons-io | （間接） | 2.18.0 | FileUtils / FilenameUtils。宣言なしで他のライブラリ経由で入っていたので明示（DJL 撤去で消えないように） |
| com.github.pemistahl:lingua | 1.2.2 | 1.2.2 | 最新 |
| com.github.gkonovalov.android-vad:silero | 2.0.10 | 2.0.10 | 最新 |
| PRDownloader（JitPack） | 1.0.2 | 同 | 最新（更新停止）。RadioGroupPlus は撤去（`widgets/NestedRadioGroup`） |

## GitHub Actions

| Action | 旧 | 新 |
|---|---|---|
| actions/checkout | v4 | **v7** |
| actions/setup-java | v4 | **v6** |
| android-actions/setup-android | v3 | **v4** |
| actions/upload-artifact | v4 | **v7** |
| softprops/action-gh-release | v2 | **v3** |

メジャー版の公開日は API 制限で未確認（タグの存在のみ確認）。CI 失敗時はこのコミットを最初に疑う。

## ネイティブ（NDK / CMake）
ローカルで `:app:externalNativeBuildRelease` を実行した結果（4 コア）：

| NDK | CMake | 結果 |
|---|---|---|
| 30.0.16248370（r30 安定版） | 4.1.2 | 失敗：vendored の `cmake_minimum_required(<3.5)` を CMake 4 が拒否 |
| 30.0.16248370 | 4.1.2 ＋ `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` | 失敗：ssplit-cpp の PCRE2 ExternalProject の出力パスが空（`/libpcre2-8.a`） |
| 30.0.16248370 | 3.22.1 | 失敗：marian 同梱 sentencepiece が新 clang で `constexpr ... must be initialized by a constant expression` |
| **28.2.13676358** | **3.31.6** | **成功**（8分31秒） |

→ **解決済み**：vendored の修正（docs/VENDORED.md「Local patches」）で NDK r30 / CMake 4.1.2 のネイティブビルドが通る（`:app:externalNativeBuildRelease` 成功、生成 .so の LOAD 整列 0x4000。同梱の `libsqlite4java.so`（AAR 由来のビルド済み）は 0x1000 のまま）。
NDK r28 の対応 API は 35 まで（minSdk 36 のネイティブ部分は 35 として扱われる旨の警告。CMake 引数は従来どおり `android-28`）。

## 今後（AGP 10 で削除予定の警告）
- ~~`android.nonFinalResIds=false`~~ → 削除済み（R の id を switch / アノテーション / static final に使う箇所なし）
- `android.enableJetifier=false` → **撤去済み**：最後の依存元 `com.github.worker8:RadioGroupPlus:1.0.1`（`com.android.support:appcompat-v7`）を自前の `widgets/NestedRadioGroup` に置き換えた。`./gradlew checkJetifier` で他に旧サポートライブラリ依存は無い
