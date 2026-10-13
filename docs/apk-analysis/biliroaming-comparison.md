# 指定 BiliRoaming リポジトリのクリップボード処理

2026-10-13 に `https://github.com/cynagenautes/BiliRoaming` の既定ブランチ `master` を取得して調査した。対象コミットは `7c792dc8fea321ff6a308ce3424668f81019df58`。公開されているブランチは調査時点で `master` のみ。

このソースでは、クリップボード監視禁止専用の設定・フックは確認できなかった。Kotlin、設定 XML、文字列、JavaScript、JNI を対象に、clipboard / primaryClip / ClipboardChecker / read_enable / OnPrimaryClipChanged と日本語・中国語の関連語で確認した。

## 確認できた実装

| 実装 | 動作 | 監視禁止との関係 |
| --- | --- | --- |
| `CopyHook` / `comment_copy` | コメント・動態・動画説明の長押しコピー処理を置換する。`comment_copy_enhance` が OFF なら `true` や `Unit` を返して元のコピー処理を実行しない。ON なら選択可能な自由コピーダイアログを表示し、「复制全部」で元処理を呼ぶ。 | コピー操作の変更。自動読み取りや変更通知は止めていない。 |
| `CopyCommentHook` / `copy_comment` | `CommentMoreMenuItemHolder.y3(...)` の after hook。メニューに `COPY` が含まれる場合、コピー後の `primaryClip.getItemAt(0).text` を取得して自由コピーダイアログに表示する。 | ユーザーの COPY 操作に続く読み取り。読み取り禁止のフックではない。 |
| `BiliBiliPackage.descCopy` | `AV%d` と `clipboard` 等を手掛かりに動画説明のコピー用メソッドを探索する。 | `clipboard` の文字列はコピー処理の検出用。監視機能の検出ではない。 |
| `SettingDialog.onCopyAccessKeyClick` | ユーザー操作で access key を `setPrimaryClip` へ書き込む。 | 手動コピー。 |

設定の「去除长按复制」の説明も「禁用长按评论/动态/视频简介复制到剪切板（减少误触）」で、長押しコピーの誤操作を減らす項目である。

起動関連の `KillDelayBootHook` は `com.bilibili.gripper.exp.a$a` の `getDelayMillis`（Play 版では `b`）の戻り値を `-1L` に置換する。クリップボードの初期化クラスやリスナーを選別して禁止する処理はここにはない。`EnvHook.configSet` にも今回調べている `clipboard.read_enable` の上書きはなく、登録されているのは `bv.enable_bv` の設定だけだった。

## ソースへの固定リンク

- [CopyHook](https://github.com/cynagenautes/BiliRoaming/blob/7c792dc8fea321ff6a308ce3424668f81019df58/app/src/main/java/me/iacn/biliroaming/hook/CopyHook.kt#L28-L40)
- [CopyCommentHook](https://github.com/cynagenautes/BiliRoaming/blob/7c792dc8fea321ff6a308ce3424668f81019df58/app/src/main/java/me/iacn/biliroaming/hook/CopyCommentHook.kt#L15-L45)
- [設定の説明](https://github.com/cynagenautes/BiliRoaming/blob/7c792dc8fea321ff6a308ce3424668f81019df58/app/src/main/res/values/strings.xml#L116-L121)
- [コピー処理の探索](https://github.com/cynagenautes/BiliRoaming/blob/7c792dc8fea321ff6a308ce3424668f81019df58/app/src/main/java/me/iacn/biliroaming/BiliBiliPackage.kt#L1619-L1687)
- [KillDelayBootHook](https://github.com/cynagenautes/BiliRoaming/blob/7c792dc8fea321ff6a308ce3424668f81019df58/app/src/main/java/me/iacn/biliroaming/hook/KillDelayBootHook.kt#L8-L15)
- [EnvHook の設定一覧](https://github.com/cynagenautes/BiliRoaming/blob/7c792dc8fea321ff6a308ce3424668f81019df58/app/src/main/java/me/iacn/biliroaming/hook/EnvHook.kt#L125-L134)

## BBZQ との比較

今回 BBZQ に実装したのは `ClipboardChecker` の自動取得、確認済みの変更通知リスナーの登録／callback、別スイッチによるページ GET の制限である。通常のコピー・入力欄への貼り付け・コピー成功照合は残す。[9.0.0 のケース解析](9.0.0-9000200/clipboard-cases.md)を参照。

この確認のために BBZQ のフック実装は変更していない。ここでの結論は上記コミットの公開ソースを対象にしたものであり、別の派生リポジトリ、過去のタグ、配布 APK の実装を調べた結果ではない。
