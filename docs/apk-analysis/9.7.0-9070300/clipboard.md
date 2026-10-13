# Bilibili 9.7.0 と既存フックの適合性

結論：**初期実装で対象にした `ClipboardChecker` の読み取りヘルパーは、9.7.0 でも同じ位置・検出条件で利用できる。** LSPosed 実機での呼び出しと UI 動作は未検証。この比較後に追加したリスナー停止とページ制限については [9.0.0 のケース解析](../9.0.0-9000200/clipboard-cases.md)を参照。ページ制限全体の 9.7.0 互換性をこの報告だけで確認済みとはしない。

ユーザー提供 APK の署名検証に成功した。manifest は `tv.danmaku.bili` / `9.7.0` / `9070300`、minSdk 24、targetSdk 35。APK SHA-256 は `43c31cafee737af7f9c56d5826da9f00396a269e5ed44abf2b076a1adc914a33`。署名証明書 SHA-256 は `93ba270f5521139ecafe4bb638ac5b1198bc548f62d9fd8f8580a079faf5910e` で、提供された 9.0.0 と同じ。

30 個すべての DEX、279,111 クラス、1,585,925 メソッドを dexlib2 3.0.9 で走査し、候補クラス・直接の呼び出し元・関連クラスを baksmali 3.0.9 で逆アセンブルした。読み取りヘルパーと変更通知の Runnable は Jadx 1.5.3 でも復元して確認した。空内容の継続処理は Java の完全復元に失敗したため Smali を根拠とする。

機械可読の [解析結果](clipboard.json) と [9.0.0 との差分](diff-from-9.0.0.json) を保存した。全参照インデックスと生成 Java / Smali はチェックアウト外の `/workspace/BBZQ-analysis/9.7.0-9070300/` にある。

## 既存実装が成立する根拠

| 検証項目 | 9.7.0 の結果 |
| --- | --- |
| 既知クラスの読み込み名 | DEX の実名は `tv.danmaku.bili.ui.clipboard.ClipboardChecker`。既存の高速反射経路が指定するクラス名と一致。 |
| 一意なメソッド形状 | `public static String c(android.content.ClipboardManager)` が唯一の `static String(ClipboardManager)`。access flags は `0x09`。 |
| DexKit のクラス検出条件 | クラス内に `clipboard.read_enable` と `ClipboardChecker` の両方が存在。 |
| DexKit のメソッド検出条件 | 上記ヘルパーは code unit `0x04` で `ClipboardManager.getPrimaryClip(): ClipData` を呼ぶ。引数・戻り値型とも既存ルールに一致。 |
| メソッドの復元 | DEX の declaring class / name / parameter types / return type は 9.0.0 と同じ。既存の MethodDescriptor と一意性チェックに適合。 |
| 起動時の登録 | 対象クラス名が変わっていないため、非同期 DexKit を待つ改名時フォールバックに入る必要がない。 |
| 版更新時のキャッシュ | 既存 fingerprint の versionCode / versionName が 9000200 / 9.0.0 から変わるため、旧版のキャッシュは再利用されない。 |

これは DEX の実データから検出条件を照合した結果。Android 上のネイティブ DexKit 実行や Xposed インターセプトを実行した結果ではない。

## 9.0.0 からの差異

| 項目 | 9.0.0 | 9.7.0 |
| --- | --- | --- |
| 読み取りヘルパーの DEX | `classes21.dex` | `classes22.dex` |
| 初回レイアウト | `tv.danmaku.bili.K.onGlobalLayout()` | `tv.danmaku.bili.X.onGlobalLayout()` |
| 読み取り Task | `ui.clipboard.f.call()` | `ui.clipboard.g.call()` |
| 空内容の継続処理 | `ui.clipboard.g.then(Task)` | `ui.clipboard.h.then(Task)` |
| 変更通知後の Runnable | `ui.clipboard.l.run()` | `VW0.D0.run()` の default branch |
| 変更通知リスナー | `ui.clipboard.j` | `ui.clipboard.k` |
| 登録タスク | `com.bilibili.gripper.clipboard.a` | `com.bilibili.gripper.clipboard.InitClipboard_CompatProducer` |
| 読み取りヘルパー内のログ呼び出し | `androidx.appcompat.widget.k.a(String,String,String)` | `Ss.l.b(String,String,String)` |

ヘルパーの Smali 本体ハッシュは変わったため、差分ツールは `changedReadHelperBodies` として検出した。ソース行・デバッグ情報を除いた命令列を直接比較したところ、差異は上表のログ呼び出し一箇所。`getPrimaryClip()`、null / itemCount 判定、テキスト取得、戻り値と分岐は一致している。フックは元のヘルパーを呼ばず空文字を返すため、このログ呼び出し先の変更にも依存しない。

## 起動・復帰・変更通知と空文字の扱い

- 起動：`X.onGlobalLayout()` の `0x1b` → `ClipboardChecker.b(ClipboardChecker$a)` → `g.call()` の `0x04` → 読み取りヘルパー `c(...)`。
- 前面復帰：`ui.clipboard.d.run()` の `0x0f` → 同じ `b(...)`。ログ文字列 `Clipboard on app forground check.` も存在。
- 変更通知：`InitClipboard_CompatProducer.doExecute(Task)` の `0x30` で `ui.clipboard.k` を登録。`k.onPrimaryClipChanged()` が `VW0.D0(2, manager, context)` を HandlerThreads に post し、Runnable の default branch の `0x11` が `c(...)` を呼ぶ。

空文字を返した場合、`h.then(Task)` の `0x10` で `TextUtils.isEmpty` が true になる。ジャンプ状態の解除、`ClipboardChecker.d=false`（`0x21`）、`TaskCompletionSource.setCancelled()`（`0x23`）、`Clipboard content is empty.` の StateFlow 通知を経て終了する。これらは 9.0.0 の空内容経路と同じであり、チェック本体を強制終了して状態を残す必要はない。

変更通知の `VW0.D0.run()` も `c(...)` の結果を `TextUtils.isEmpty` で判定する。空文字は null 扱いにされ、内容判定より前に return へ進む。Runnable 全体には別用途の case 0 / 1 もあるため、それ自体を無効化する必要はない。読み取りヘルパーだけを対象にする既存フックは、このクラス移動にも依存しない。

Jadx は `ClipboardChecker.b(...)` と `h.then(Task)` の完全復元に失敗したため、Task の組み立てと空内容の分岐は Smali で確認した。移動した synthetic Runnable はインライン化を無効にして Java 復元できた。継続処理 `h` の Java 出力名は `C0663h` だが、これは Jadx の別名であり、検出やフックには DEX 実名を使う。復元失敗時の `UnsupportedOperationException` は生成 Java の代替表現であり、APK 内の実装ではない。

## 検証範囲

元の解析と別の出力ディレクトリで再解析し、同じ APK 識別情報・唯一のヘルパー・移動した呼び出し元を確認した。今回、解析ツールは直接の呼び出し元をパッケージ外でも Smali に保存するよう補い、差分処理のテスト 3 件も成功した。フック本体とアプリの設定は変更していない。

実機では、設定を ON にした状態で 9.7.0 のコールドスタート・前面復帰・前面でのクリップボード変更を確認する。自動判定の停止、元のクリップボード内容の保持、手動コピー・貼り付け、OFF 時の復元を確認する項目は [9.0.0 の実機手順](../9.0.0-9000200/clipboard.md) と同じ。ここで検証した範囲は自動判定の読み取りヘルパーと空内容の後続処理である。
