# AVPlayer Preview

[English](README.md) | 日本語

ローカルのMP4・M4V・MOVを、事前変換せずにIntelliJ IDEAのエディタータブ内でプレビューするmacOS専用プラグインです（試作版）。
macOS標準のAVPlayerでデコードするため、FFmpeg・VLC・JCEF・HTTPサーバーは不要で、元ファイルも書き換えません。

> **試作版です。** ネイティブ再生はテスト済みですが、IDE画面内での再生・音声・操作はまだ検証していません。
> 詳しくは「[検証状況](#検証状況)」を参照してください。

## 動作環境

- macOS 13以上、Apple Silicon（arm64）のみ。Intel Macには対応していません。
- IntelliJ IDEA 2026.3以降（build 263〜）。2026.3でビルド・確認しており、それ以降のバージョンは未検証です。
- ローカルファイルのみ。Remote Developmentは対象外です。

## インストール

### A. プラグインリポジトリを登録する（推奨・自動更新あり）

1. `Settings → Plugins` を開き、歯車メニュー → `Manage Plugin Repositories…` を選びます。
2. `+` で次のURLを追加し、OKを押します。
   ```
   https://raw.githubusercontent.com/junara/avplayer-preview/main/updatePlugins.xml
   ```
3. `Marketplace` タブで `AVPlayer Preview` を検索してインストールし、IDEを再起動します。

新しいバージョンは、IDEの通常のプラグイン更新通知で届きます。複数のMacで使う場合は、それぞれのIDEで同じURLを登録します。

### B. ZIPから手動でインストールする

1. [Releases](https://github.com/junara/avplayer-preview/releases) の **Assets** から
   `avplayer-preview-<version>.zip` をダウンロードします。
   「Source code (zip)」はソース一式なので選ばないでください。
2. `Settings → Plugins` → 歯車メニュー → `Install Plugin from Disk…` でそのZIPを選び、IDEを再起動します。

自分でビルドした場合は `dist/avplayer-preview-<version>.zip` を同じ手順で指定します。

### アンインストール

`Settings → Plugins → Installed` で `AVPlayer Preview` を無効化またはアンインストールし、IDEを再起動します。
ネイティブコードを含むため、更新・無効化・アンインストール後は必ず再起動してください。

## 使い方

ProjectビューでMP4/M4V/MOVをダブルクリックし、`Play` を押します。

- 再生/一時停止、タイムライン移動、音量、倍速（0.5 / 1 / 1.5 / 2倍）
- `Info` で動画情報パネルを表示/非表示
- 別タブへ切り替えると一時停止し、戻ったときは手動で再生します。
- 終端で `Play` を押すと先頭から再生します。

動画がこのプラグインで開かない場合:

- 同じ拡張子を扱う別のプラグイン（Jetplayなど）を無効にします。
- `Settings → Editor → File Types` で、`mp4` / `m4v` / `mov` が `AVPlayer Video` に関連付けられているか確認します。

### 動画情報パネル

- **ファイル**: 名前、サイズ、コンテナ（拡張子ではなくファイル先頭の `ftyp` ブランド、または旧QuickTime形式の先頭atomで判定）、長さ、全体ビットレート
- **映像**: コーデック、解像度、回転、左右/上下反転、フレームレート、ビットレート、ビット深度、色空間、HDR種別
- **音声**: コーデック、サンプルレート、チャンネル数、ビットレート、言語

表の行は選択してコピーできます。情報はパネルを初めて開いたときに再生とは別スレッドで読み込み（最大10秒）、再生に失敗した動画でも表示します。
読み込めなかった項目は `Metadata` 行に理由（または時間切れ）が表示され、IDEログにも記録されます。パネルを開き直すと再読み込みします。

### 記憶される設定

- 音量はIDE全体で記憶し、次に開いた動画とIDE再起動後に反映します（開いている別タブには反映されず、最後に操作したタブの値が保存されます）。
- 動画情報パネルの表示/非表示もIDE全体で記憶します。

## 制限

- 表示用の映像フレームは**長辺最大1280px・約30fps**に縮小します（デコード後の縮小で、変換ファイルは作りません）。音声と再生時刻はAVPlayerが管理します。
- 描画はCPUメモリコピーで、ゼロコピーのGPU描画ではありません。
- 再生できるのはAVFoundationが対応するコーデックのみで、すべてのMP4が再生できるとは限りません。
- HDR（HDR10 / HLG）はSDR（BT.709）に変換して表示します。HDRのままの表示、回転・ピクセルアスペクト比の反映、字幕・複数音声トラックの選択には対応していません。
- ネイティブコードがIDEプロセス内で動作します。初めて使うときは作業を保存してから試してください。

## 検証状況

| 項目 | 状況 |
| --- | --- |
| ビルド | IntelliJ IDEA **IU-263.7307.200**（付属JBR **25.0.4.1**）で成功。arm64 dylib、ad-hoc署名を含む |
| ネイティブ再生テスト（GUIなし、JNI経由） | 2026-11-13、Apple Silicon で全項目PASS（内容は「[テスト](#テスト)」を参照） |
| IDE画面内での再生・音声・シーク・タブ操作 | **未検証** |
| 4K60・HEVC 10bit・HDR10/HLG | 2026-11-14、GUIなしの計測で再生と動画情報を確認。エディターと同じ取得間隔の再現で約30fps。SDRへの変換は自動テストでPQのみ確認し、HLGは変換されることだけ手動で確認（正確さは未確認）。IDE画面内での見た目は未検証 |
| 縦向き動画 | 未検証（回転・反転は動画情報の表示のみテスト済み） |

### 再生エラーが出る場合

`AVFoundationErrorDomain -11821` や `NSOSStatusErrorDomain -12911`（`kVTVideoDecoderMalfunctionErr`）が表示される場合は、
プラグインではなくmacOS側のデコーダーの問題の可能性があります。
JNIもIDEも使わないAVFoundation単体のプローブで同じエラーが出るか確認してください。

```sh
xcrun clang++ -fobjc-arc tests/avfoundation_probe.mm \
  -framework Foundation -framework AVFoundation -framework CoreVideo \
  -o build/avfoundation-probe
build/avfoundation-probe '/path/to/video.mp4'
```

## 開発

### 仕組み

Kotlin（エディターUI）→ JNI → Objective-C++ → AVPlayer → AVPlayerItemVideoOutput → Swingで描画します。
AVPlayerViewをネイティブウィンドウとして埋め込む方式ではありません。
AVPlayerの操作はAppKitのメインキューで、JNI呼び出しは専用ワーカースレッドで直列に実行します。
タブを閉じると非同期でAVPlayerを停止し、リソースを解放します。

### ビルド

必要なもの: IntelliJ IDEA 2026.3（付属のKotlinプラグインとJBRを使用）、Xcode Command Line Tools、Python 3。
インストール済みのIDEをSDKとして使うため、Gradleや外部依存のダウンロードは不要です。

```sh
python3 scripts/build.py
```

IDEが `~/Applications/IntelliJ IDEA.app` 以外にある場合:

```sh
IDEA_HOME='/Applications/IntelliJ IDEA.app/Contents' python3 scripts/build.py
```

出力: `dist/avplayer-preview-<version>.zip`（バージョンは `plugin.xml` の `<version>`）

### テスト

GUIを使わず、実際のJNI経由でネイティブ再生をテストします。
テスト用の動画はFFmpegで `build/` に生成するため、**FFmpegはテスト時のみ必要**です（回転・反転の確認にはFFmpeg 7以上が必要で、古い場合はその確認だけをスキップします）。
音量ゼロで実行し、既存の動画には触れません。

```sh
python3 scripts/build.py
python3 scripts/test_native.py
```

テスト内容: 存在しないパス、オープン、デコード、時間進行、一時停止、シーク、倍速、
動画情報（コーデック・解像度・フレームレート・音声形式、29.97fps・回転・左右反転）、3回の生成/破棄、二重close、
フレームバッファの再利用、全ピクセルの不透明、色順（BGRA→xRGBでR/Bが入れ替わらないこと）、
HDR（PQ）がSDRに変換されること（libx265がない場合はスキップ）。60秒でタイムアウトします。
日本語を含むパス（`build/日本語 sample.mp4`）で実行します。

IDE画面内で確認したい項目:

- 横向きMP4で映像と音声が出るか、音ズレしないか
- シーク、終端からの再生、倍速、音量の変更
- ウィンドウのリサイズ、タブの切り替え、タブを閉じたときの停止・解放
- ファイルの開き直しや複数タブでのフリーズ・メモリ増加
- 縦向き・HEVC・4Kなどの動画

### リリース

1. `plugin.xml` の `<version>` を上げ、`<change-notes>` を更新してコミットします。
2. 事前確認（git・GitHubは変更しません）。`build/updatePlugins.xml` とリリースノートを確認します。
   ```sh
   python3 scripts/release.py --dry-run
   ```
3. 本番（`gh` CLIのログインが必要）:
   ```sh
   python3 scripts/release.py
   ```
   ビルド → `updatePlugins.xml` の更新・コミット → タグ `v<version>` のpush → GitHub ReleaseへのZIP添付 → `main` のpush、の順に実行します。
   ZIPを公開してから `main` をpushするため、`updatePlugins.xml` が存在しないZIPを指すことはありません。

### ファイル構成

| パス | 内容 |
| --- | --- |
| `src/main/kotlin/.../VideoEditor.kt` | エディター・UI・ワーカー |
| `src/main/kotlin/.../VideoFileType.kt` | ファイル形式の登録 |
| `src/main/java/.../NativePlayer.java` | JNI宣言とネイティブライブラリの読み込み |
| `native/NativePlayer.mm` | AVPlayer・フレーム取得・縮小・動画情報 |
| `src/main/resources/META-INF/plugin.xml` | プラグイン定義（バージョン・変更履歴） |
| `scripts/build.py` | インストール済みIDEを使ったビルド |
| `scripts/release.py` | GitHub Releaseへの公開と `updatePlugins.xml` の更新 |
| `updatePlugins.xml` | カスタムプラグインリポジトリ（`release.py` が生成） |
| `tests/` | JNI経由のテストとAVFoundation単体プローブ |

### 参考

- [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer)
- [AVPlayerItemVideoOutput](https://developer.apple.com/documentation/avfoundation/avplayeritemvideooutput)
- [Build Number Ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- [Custom Plugin Repository](https://plugins.jetbrains.com/docs/intellij/custom-plugin-repository.html)

## コントリビューション

Pull Requestは受け付けていません。MIT Licenseの範囲で、forkして自由に改変できます。

## ライセンス

[MIT License](LICENSE)
