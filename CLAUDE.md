# CLAUDE.md

IntelliJ IDEA 用の macOS 専用動画プレビュープラグイン。Kotlin UI → JNI → Objective-C++ → AVPlayer。
全体像・使い方は README.md（英語）/ README.ja.md（日本語）を参照。ここには開発時の留意点だけを書く。

## ビルド・テスト

- ビルド: `python3 scripts/build.py`（インストール済み IntelliJ IDEA 2026.3 / build 263 を SDK として使う。別の場所なら `IDEA_HOME=...`）
- テスト: `python3 scripts/build.py && python3 scripts/test_native.py`（GUI なし・JNI 経由。FFmpeg で `build/` にテスト動画を生成）
- コードを変更したら、上の両方を実行してから完了と報告する。失敗したら出力をそのまま伝える。

## 守ること

- **外部依存を増やさない**: Gradle・Maven・FFmpeg（テスト以外）・VLC・JCEF・HTTP サーバーは導入しない。ビルドはオフラインで完結させる。
- **元ファイルを書き換えない・変換ファイルを作らない**: 動画はデコード後に縮小して表示するだけ。
- **対応範囲を変えない**: macOS 13+、Apple Silicon（arm64）のみ、`since-build="263"`、JVM target 21。変える場合は事前に相談する。
- **スレッドモデルを崩さない**: AVPlayer の操作は AppKit のメインキュー、JNI 呼び出しは専用ワーカースレッドで直列実行。EDT やメインキューをブロックしない。
- **ネイティブコードは IDE プロセス内で動く**: クラッシュ・リーク・二重解放は IDE ごと落とす。close の冪等性とリソース解放を必ず確認する。
- JNI のシグネチャを変えたら `NativePlayer.java` と `native/NativePlayer.mm` の両方を揃える（ヘッダは `javac -h` で生成される）。

## ドキュメント

- README は英語（`README.md`）と日本語（`README.ja.md`）の2つ。内容は常に揃え、片方を変えたらもう片方も同じ変更をする。
- それ以外のドキュメント（CLAUDE.md など）は日本語。
- コード内のコメント・ログ・エラーメッセージは英語。
- 挙動・制限・対応状況が変わったら両方の README の該当箇所（特に Limitations / 制限、Verification status / 検証状況、Tests / テスト）も更新する。
- 実際に確認していないことを「検証済み」と書かない。IDE 画面内での再生などは自分では確認できないので、未検証のまま残すか、確認手順をユーザーに示す。

## コミット

- コミットメッセージは [Conventional Commits](https://www.conventionalcommits.org/ja/v1.0.0/) 形式にする: `<type>(<scope>): <description>`
- type: `feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `build` / `chore` / `ci` / `style` / `revert`
- scope は任意。使う場合は `native` / `editor` / `build` / `release` / `readme` など変更箇所を示す短い語にする。
- 破壊的変更は `feat!:` のように `!` を付け、本文に `BREAKING CHANGE:` を書く。
- コミットメッセージ（subject・本文とも）は英語で書く。subject は命令形・小文字始まり・末尾ピリオドなし。
- 例: `fix(native): prevent crash on double close`、`docs(readme): update verification status`
- リポジトリは公開済み。push 済みのコミットやタグを書き換えない（rebase・amend・squash・force push しない）。利用者の clone と `updatePlugins.xml` が指すリリースが壊れる。

## リリース

- `python3 scripts/release.py`（`--dry-run` なし）はタグ push・GitHub Release 公開・`main` の push を行う。**ユーザーの明示的な指示なしに実行しない。** 確認は `--dry-run` で行う。
- `updatePlugins.xml` は `release.py` が生成するので手で編集しない。
- バージョンを上げるときは `plugin.xml` の `<version>` と `<change-notes>` を一緒に更新する。
