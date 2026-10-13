# Bilibili 9.0.0 の自動クリップボード参照

解析対象はユーザー提供の APK。署名検証に成功し、manifest は `tv.danmaku.bili` / `9.0.0` / `9000200`、minSdk 24、targetSdk 35。APK SHA-256 は `3d639ed495089da9d8707545054b0a4c07c27033f3667d8d78c32dd073969a20`。観測した署名証明書 SHA-256 は `93ba270f5521139ecafe4bb638ac5b1198bc548f62d9fd8f8580a079faf5910e`。

29 個すべての DEX（263,639 クラス、1,527,820 メソッド）を dexlib2 3.0.9 で走査した。対象クラスと関連クラスを baksmali 3.0.9 で逆アセンブルし、`ClipboardChecker` を Jadx 1.5.3 でも確認した。比較基準の機械可読データは [clipboard.json](clipboard.json)。再解析・上位版との差分取得は [解析ツール](../../../tools/apk-analysis/README.md) を使用する。

## 確認した経路

| 起点 | 経路／根拠 |
| --- | --- |
| ランチャーからの初回描画 | `classes20.dex` の `tv.danmaku.bili.K.onGlobalLayout()` に `start check clipboard from launcher`。code unit `0x1b` で `ClipboardChecker.b(ClipboardChecker$a)` を呼ぶ。 |
| 前面復帰 | `classes21.dex` の `tv.danmaku.bili.ui.clipboard.d.run()` に `Clipboard on app forground check.`。`0x0f` で同じチェックを呼ぶ。 |
| 自動判定の読み取り | `ClipboardChecker.b(...)` が `f(ClipboardManager)` を作って Bolts Task を起動。`f.call()` は `0x04` で `ClipboardChecker.c(ClipboardManager)` を呼ぶ。 |
| クリップボード変更通知 | `classes8.dex` の `com.bilibili.gripper.clipboard.a.doExecute(Task)` が `addPrimaryClipChangedListener` を登録。`j` → `l.run()` → `ClipboardChecker.c(...)`（`0x06`）で内容を読む。 |
| 実際のフレームワーク参照 | `ClipboardChecker.c(ClipboardManager): String` の `0x04` が `ClipboardManager.getPrimaryClip(): ClipData`。その後 `getItemAt(0).getText()` を取得。 |

`c` はこのクラスで唯一の `static String(ClipboardManager)`。Java 復元と Smali の両方で確認した。文字列 `getPrimaryClip: clipdata = ` と `privacy` のログもこのメソッドにある。起動チェック `b` には `clipboard.read_enable` と `ClipboardChecker` がある。

Jadx はチェック本体 `b` を完全には復元できず、生成 Java に `UnsupportedOperationException` の代替表現を出した。これは APK 内の実装ではない。このメソッドの Task 起動、状態更新、分岐は Smali を根拠とする。読み取りメソッド `c` 自体の Java 復元は成功している。

## 無効化する位置

BBZQ はチェック本体を終了させず、読み取りヘルパーで空文字を返す。元の読み取り処理は実行されない。通常の「クリップボードが空」の結果と同じ経路を通す。

`f.call()` の結果を受ける `g.then(Task)` は `TextUtils.isEmpty` で空を判定し、ジャンプ状態を false に戻し、`ClipboardChecker.d`（チェック中）を false にし、TaskCompletionSource を cancel する。続いて StateFlow に `Clipboard content is empty.` の結果を通知する。この早期経路では、後続の内容判定・自動ジャンプ・`setPrimaryClip` による書き換えを行わない。変更通知から呼ばれる `l.run()` も空文字なら後続処理へ進まない。

全 `ClipboardManager.getPrimaryClip()` を一括で止めるフックは入れない。`AppCompatEditText.onTextContextMenuItem`、ライブ入力欄、Compose、Web/ゲーム等の別経路も APK に存在するため、手動貼り付けとこの自動判定を区別する。他の SDK や機能のクリップボード読み取り全般を無効化する機能ではない。

## BBZQ との統合

- 設定 → 启动净化 → **禁止自动读取剪贴板**。既定 OFF。設定のエクスポート／インポートにも含める。
- メインプロセスのみ、読み取りヘルパーを一度登録し、呼び出し時に設定を読む。OFF の場合は元の処理を通す。
- `Application.attach` 後の既存ランタイム初期化で、既知 `ClipboardChecker` の唯一のシグネチャを反射で見つける。MainActivity の初回レイアウト前に登録できる構成にして、非同期 DexKit 完了待ちを避ける。
- フルスキャンではクラスの文字列と `getPrimaryClip` の invoke を確認。既知クラスが改名された場合は文字列から候補を探す。改名時は初回起動の遅延検出となる可能性があるため、上位 APK の解析で必ず再確認する。
- 候補・復元対象が曖昧な場合は登録しない。ステータスは `ClipboardReadBlockHook.AutomaticRead` に表示する。
- `BiliHookSymbols` の schema は 40、DexKit rule は 62。旧キャッシュを再走査する。

Debug ビルド・JVM テストはクラウドで検証した。実機上の LSPosed 呼び出し、OS のクリップボード通知、起動・復帰の UI は未検証。上位バージョンとの互換性も、この 9.0.0 の静的解析だけでは確認済みとしない。

## 実機での確認

1. Modern API 対応 LSPosed でモジュールを有効化し、対象をスコープへ追加。9.0.0 を強制終了して、設定を ON にしてから起動する。
2. Bilibili の共有 URL / BV ID を他アプリでコピーしてからコールドスタート・前面復帰し、自動ジャンプやこの判定経路からの読み取り通知が出ないこと、元のクリップボード内容が保持されることを確認する。
3. アプリ前面でクリップボードを変更し、設定画面や入力欄で手動コピー・貼り付けを試す。
4. 設定を OFF に戻し、自動判定が元に戻ること、スキャン状態に FOUND と読み取りヘルパーが表示されることを確認する。
