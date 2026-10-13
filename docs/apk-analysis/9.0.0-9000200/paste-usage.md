# 9.0.0：貼り付けと自動利用の用途

対象はユーザー提供の `tv.danmaku.bili` 9.0.0 / 9000200 APK。ハッシュと全 DEX の取得箇所は [元の解析](clipboard.md)と[API インデックス](privacy-inventory.json)を参照。追加でクリップボード判定パッケージ、検索入力欄、招待画面、IM のコピー action、Ouro エディタを Jadx 1.5.3 と baksmali 3.0.9 で確認した。Jadx が復元できなかった分岐は Smali を根拠とした。

## 検索欄への自動貼り付け

今回追った 13 箇所の内容取得と、それらの利用側には、システムのクリップボードの文字列をネイティブ検索欄へ自動で `setText` / `setQuery` する固定処理は見つからなかった。

`tv.danmaku.bili.widget.SearchView$QueryText` は AppCompatEditText を継承し、通常の貼り付けメニューを使用する。外側の `SearchView.setQuery(CharSequence)` は渡された引数を入力欄へ設定する処理で、クリップボードを取得していない。`onQueryRefine` からも呼ばれるが、これも渡された検索候補を設定する経路である。検索パッケージで確認した直接のクリップボード操作は、検索結果などからコピーする書き込み側だった。

ただし、下記の自動認識ではサーバーやルールが決めた URL へ遷移できる。その URL に検索条件が含まれる場合まで排除する結論ではない。現在配信されているルール・応答や Web ページの JavaScript を取得して実行した調査ではない。

## 自動取得の実際の用途

```text
起動・前面復帰
  → ClipboardChecker が内容を取得
  → 圧縮された共有情報を必要に応じて復元
  → ローカルの形式、サーバー配信の正規表現・条件に照合
  → 該当する共有情報を解決
  → 対象ページを開く／案内ダイアログを開く
```

| 用途 | 確認した処理 |
| --- | --- |
| 動画・番組などの共有情報を開く | `w` / `v` / `t`、`ui.splash.d` がエンコードされた `FromUriOpen@bilibili://...`、av / ss / ep 形式、Bilibili 等のリンクを扱う。動画の認識結果には `bilibili://video/...` の識別子も含まれる。検索欄に文字を貼る処理ではなくルーターへの遷移。 |
| 共有コードの解決 | `B` / `F` がコードを UserGrow API で解決し、応答の URL / schema を開く。既定の形式には `GHA...` と `b23$...$` があり、設定で変わり得る。 |
| キャンペーン等の案内 | サーバー由来の `popup_mode` / `popup_rule` に従って、直接遷移または `activity://main/user_grow_dialog` の表示へ進む。赤包リンクのローカル形式もある。 |
| 自分自身のコピーの識別 | 前面での変更通知 → `l.run` が対象形式の内容を `clipboard.inner_copy_content` に記録し、再度の自動認識で使用する。入力欄への貼り付けのための保存先ではない。 |

`ClipboardResult` は PAGE_POP_MODE=1 と DIALOG_POP_MODE=2 を持つ。`ClipboardChecker.a(y)` がモードで分岐し、`e.onShow` は結果 URL を BLRouter に渡し、`to0.h.onShow` は案内ダイアログを開く。表示対象もホーム・メイン画面・全画面等の条件で制限される。コピー内容を取得するだけの処理ではなく、共有コンテンツを開く便利機能とキャンペーンの誘導が同じ自動判定に含まれる。

## リンク判定の発動条件

取得と判定は別段階である。起動や前面復帰の `ClipboardChecker.b(...)` が、チェックの重複と `clipboard.read_enable` を確認し、`f.call()` → `c(...)` で実際の内容を取得する。その後、`g` / `h` と各 matcher が共有情報を判定する。リンクに該当しなかった場合も、判定に到達する前に読み取りは済んでいる。

ローカルのエンコード形式に加えて、`x/share/clipboardRules` から配信される正規表現を使用する。`RegexRule.check()` は初回起動向け・コールドスタート向け・共通という起動条件と、必要ならログイン状態を確認する。ルールにより自身のコピーを除外する条件もある。動画向けの `u.a(...)` では `getClipAutoJump(true)`、TeenagersMode、BVCompat の条件を確認する。別の matcher `z` には RestrictedMode / multi-window の除外がある。これらは経路ごとの条件で、全ルールが同じ条件ではない。

さらに認識後の `ClipboardResult.checkValid()` が URL・モード・表示ルールを検証し、`checkPage()` が現在の画面で開いてよいかを確認する。合致すれば直接遷移または案内表示へ進む。普通の任意 URL をすべて自動で開く処理ではない。

前面中の変更通知 `j` → `l.run()` は、ここで確認した実装では自身のコピー識別情報の保存を行う。この callback 自体からリンク先への遷移は行っていない。起動／復帰の判定とは区別する。

## 手動操作で有意義に使われる箇所

| 場所 | 実際の役割 | 現在の BBZQ の扱い |
| --- | --- | --- |
| 検索欄・コメント・ライブ入力など | コピーした文章や番号を入力する通常の貼り付け。Compose には貼り付け可否・MIME を確認する処理もある。 | 自動監視スイッチ・ページスイッチとも対象外。 |
| Ouro のエディタ | `invokeSuspend` が ClipData の text / HTML を読み、エディタ内の貼り付け action に渡す。ログにも `paste with html` / `paste with text` がある。供給済みテキストを使い、システム内容を読まない分岐もある。 | ページスイッチ ON でシステム内容の取得を制限。独自貼り付けが使えなくなる場合がある。 |
| Web / Mall / ミニゲーム | GET API をページへ返す。独自貼り付け・コード入力に利用できるが、ページスクリプトの呼び出しも可能。 | ページスイッチで別制御。APK だけでは各呼び出しのジェスチャーを確定できない。 |
| プレイヤーのインタラクション | `UpdateClipboard` で content が null なら取得、非 null ならコピー。 | ページスイッチは取得側だけを制限。 |
| 招待コード | `kntr.app.tribee.invite.page.e.invoke()` が setText でコードをコピーし、getText で一致を確かめて成功表示する。貼り付けや自動取得の処理ではなかった。 | 保持。 |
| 広告操作・コメントコピー・コピー API | コピー直後の読み戻しで成功／失敗を確認する。 | 保持。 |
| IM の `bindClipboardAction` | action の文字列を `Z11.c.a(text)` へ渡すコピー処理。名前に Clipboard があるが内容取得ではない。 | 保持。 |

## 自動取得に伴う別の処理

自動判定は認識結果だけを扱うとは限らない。

- `ClipboardChecker.i` の Pattern は `H3.p0.invoke` の case 8 から取得する。`DeviceDecision.dd("dd.clipboard.report_rules", "")` が空でなければ正規表現を作る。`g.then` は、その正規表現が取得・復元した文字列に一致した場合、`text` にその文字列を入れ、`main.active.clipboard.get.click` を `Neurons.reportClick` へ渡す。空の設定なら Pattern は null、非一致ならこのイベントは呼ばれない。無条件に全コピーを送るという結論ではなく、条件付きで文字列を計測イベントへ載せるコードが存在するという確認。
- `c.fetchJumpResult` の Retrofit API は `https://api.bilibili.com/x/share/clipboardMeta`。一致した共有情報を解決する経路から呼ばれ、`data` / `business` / 起動パターン等を渡す。ルール取得は `x/share/clipboardRules`。API 呼び出しの実装を確認したもので、実際の通信キャプチャではない。
- `g.then` の code unit `0x1b6` と `h.then` の `0x176` には、形式の一致後に `ClipData.newPlainText(..., "")` を `setPrimaryClip` へ渡す処理がある。認識した内容を空に書き換えるため、元のアプリの自動判定が動く状態では、コピーした共有情報が残らない場合もある。

現在の自動監視停止は取得ヘルパーで空を返し、`g.then` の空判定で終了させる。この自動判定経路の認識・計測イベント・認識後の書き換えには進まない。共有リンクやコードをコピーしてアプリを開くだけで対象へ進む便利機能も停止する。通常の入力欄にユーザーが貼り付ける処理は別経路である。

## 根拠の保存先

追加した Java 復元と Smali は、チェックアウト外の `/workspace/BBZQ-analysis/9.0.0-9000200/paste-usage/` に保存した。特に以下を確認した。

- `repro/smali/classes21.dex/tv/danmaku/bili/ui/clipboard/{g,h,D,c,ClipboardChecker}.smali`
- `paste-usage/smali/classes13.dex/H3/p0.smali`
- `paste-usage/smali/classes21.dex/tv/danmaku/bili/{widget/SearchView,ui/splash/d,ui/main/usergrow/e}.smali`
- `paste-usage/smali/classes18.dex/kntr/app/tribee/invite/page/e.smali`
- `cases/smali/classes19.dex/kntr/common/ouro/core/plugin/pasteboard/OuroPasteboardPlugin$spec$1$1$3.smali`

今回は解析資料を追加した。フックの挙動は変更していない。実機での画面・OS・IME の動作は未検証であり、上位版での同じ用途の保証ではない。
