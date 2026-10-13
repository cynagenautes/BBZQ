# Bilibili 9.0.0：監視、コピー、貼り付けの区別

対象 APK とハッシュは [clipboard.md](clipboard.md) のとおり。29 DEX 全体を再走査し、内容の取得、メタデータ、変更通知の登録、書き込みを区別した。生の API 呼び出しと code unit オフセット、リスナー実装、検索入力欄、ページ候補は [privacy-inventory.json](privacy-inventory.json) に保存した。Jadx 1.5.3 の復元結果を baksmali 3.0.9 の分岐・invoke と照合した。復元できないメソッドは Smali を根拠とする。

## 「別の SDK」の訂正

前の説明では、クリップボードを読む別経路を「別の SDK」とまとめていた。しかし、読み取りがあるだけでは監視と判断できない。9.0.0 の DEX 内の明示的な `addPrimaryClipChangedListener` 呼び出しは **1 箇所**、実装型も **1 種**だった。`com.bilibili.gripper.clipboard.a` が `tv.danmaku.bili.ui.clipboard.j` を登録する経路で、独立した広告 SDK の常時監視を確認したという意味ではない。

広告関連の `Z11.a` には読み取りがあるが、インターフェース `Z11.c.b()` の呼び出し元は `AdCommentDialogHostContentKt$PasteboardDialog$1$1.invokeSuspend()` だった。ここでは `Z11.c.a(text)` でコピーした直後に読み戻し、同じ文字列かを照合して成功／失敗を表示し、外部画面を開く。周期的な読み取りではない。この確認を一律に止めると、コピー自体が成功していても「复制失败」となるため残す。

全 DEX の API 呼び出し箇所数は、`getPrimaryClip` 13、`hasPrimaryClip` 5、`getPrimaryClipDescription` 3、`addPrimaryClipChangedListener` 1、`setPrimaryClip` 86、旧 `setText` 5、`clearPrimaryClip` 1。これはソース上の参照数で、実行回数ではない。API 名そのものを const-string に持つ反射候補は今回検出されなかった。ネイティブコード・実行時生成コードまで含む無監視の証明ではない。

## 操作ごとの結果

| ケース | 9.0.0 の処理 | 自動監視停止 ON | ページ読み取り停止 ON |
| --- | --- | --- | --- |
| 他アプリでコピーして起動／前面復帰 | Launcher → `ClipboardChecker.b` → Task → `c` で自動判定 | 読まず、空結果で判定を完了 | この経路の担当は自動監視スイッチ |
| アプリ前面でクリップボードが変わる | `j.onPrimaryClipChanged` → `l.run` → `c` | リスナー登録とコールバック、内容の取得を停止 | 同上 |
| アプリ内でコピーして検索欄へ貼る | コピーは `setPrimaryClip` 等。検索入力欄は EditText の貼り付け | コピーと貼り付けを保持 | コピーと貼り付けを保持 |
| ライブ入力欄に貼る | `LiveRoomInputEditTextView.onTextContextMenuItem` → 読み取り → EditText | 保持 | 保持 |
| Compose の入力欄で選択／貼り付け | メタデータで貼り付け可否を確認し、入力操作で取得 | 保持 | 保持 |
| Web の `getClipboardData` | `Z50.o.g` の GET 分岐が JSON 応答 | 保持 | 空の `data` を返す |
| Mall Web の取得要求 | `MallWebFragmentV2.Lf` → `P.run` → `e.a` → JSON 応答 | 保持 | 空の `content` を返す |
| Ouro ページ内エディタの独自貼り付け | PasteboardPlugin の coroutine がシステム内容を取得 | 保持 | システム内容の代わりに空のクリップを渡す。独自貼り付けは制限される |
| ミニゲームの `getClipboardData` | `handleGetClipboardData` の coroutine → getter → 応答 | 保持 | 空の内容を返す |
| プレイヤーの `UpdateClipboard` | content=null は READ、非 null は WRITE | 保持 | READ のみ空。WRITE は空文字のコピーを含め保持 |
| コピー成功の確認 | 書き込んだ内容を読み戻して比較 | 保持 | 保持 |

貼り付け・自動認識の用途を追加で追った結果は [paste-usage.md](paste-usage.md)。共有リンク／コードの自動遷移、エディタへの text / HTML 貼り付け、招待コードのコピー確認、条件付きの計測イベント、認識後のクリップボード書き換えを記録した。

変更通知の `l.run()` は、アプリ自身のコピー内容を `clipboard.inner_copy_content` に記録する処理も含む。これは自動判定側の識別用で、EditText が貼り付けデータを取得するための依存ではない。自動監視停止 ON ではこの記録も止め、起動・復帰の判定も空で終了する。コピーしたデータはシステムのクリップボードに残る。

検索入力欄の具体的な根拠は `tv.danmaku.bili.widget.SearchView$QueryText`（classes21.dex）。親は `AppCompatEditText` で、`onTextContextMenuItem` の上書きはない。親の実装は Android の paste `0x1020022` / pasteAsPlainText `0x1020031` を処理し、通常の EditText 経路へ渡す。このクラスも `ClipboardChecker.c` の呼び出し元ではない。IME 経由の貼り付けも変更対象にはしていないが、端末上の動作検証は必要。

ページからの読み取りには、自動スクリプトと独自「貼り付け」ボタンが同じ API を呼べる。APK だけでジェスチャーを確定できないので、ユーザーの選択に従って別スイッチにした。ページ読み取り停止 OFF ならこれらの便利機能を保持し、ON ならページの読み取り要求全体を制限する。WebView の通常の入力欄へ Android の貼り付けメニューで貼る操作は、これらの API 境界には入らない。

## 13 箇所の内容取得の分類

| DEX / 取得を行うクラス・メソッド | 分類と確認した利用側 |
| --- | --- |
| classes.dex / `AppCompatEditText.onTextContextMenuItem` | 手動貼り付け。保持 |
| classes.dex / `androidx.compose.ui.platform.j.a` | Compose の入力・選択用 getter。保持 |
| classes14.dex / `Z11.a.d` | 広告コメントのコピー直後の照合。保持 |
| classes16.dex / `com.mall.common.utils.e.a` | `P.run` からの Mall Web 取得。ページスイッチ |
| classes19.dex / `OuroPasteboardPlugin$spec$1$1$3.invokeSuspend` | ページ内エディタのシステム内容取得。ページスイッチ |
| classes21.dex / `Ih1.l.c` | プレイヤー RPC の READ 分岐。ページスイッチ |
| classes21.dex / `ClipboardChecker.c` | 起動／復帰／変更通知の自動取得。自動監視スイッチ |
| classes4.dex / `AbilityJsbService$copyToClipboard$2` の Ghost getter | 書き込み後の照合。保持 |
| classes4.dex / `CopyToClipboardService$execute$2$1` の Ghost getter | 書き込み後の照合。保持 |
| classes6.dex / `LiveRoomInputEditTextView.a` | 入力欄の手動貼り付け。保持 |
| classes8.dex / `ClipboardHelper.read` | 直接の利用側は `wg.E$t.call`、コメントの `O.invoke`、広告操作 `m8.d.a`。コピー処理／その結果の照合。保持 |
| classes9.dex / `Z50.o.g` | Web GET。SET 分岐は保持 |
| classes9.dex / `ClipboardAbility$handleGetClipboardData$1` の Ghost getter | ミニゲーム GET。SET coroutine は保持 |

`ClipboardHelper.read` と同様に、曖昧なユーティリティの getter 全体を止めず、その利用側の操作を追った。`m8.d.a` では広告操作の条件によってコピー書き込みを飛ばして照合へ進む分岐もあるが、変更通知や周期的な監視とは別の操作経路だった。

## 実装と版比較

設定 → 启动净化 に既定 OFF の二つのスイッチを置いた。

- **禁止自动读取剪贴板**：`ClipboardChecker` の取得、確認済みリスナーの登録、登録済みリスナーの callback を止める。停止中に省略した登録を再開するため、切り替え後は Bilibili の全プロセスを再起動する。
- **禁止网页与小游戏读取剪贴板**：確認済みの 5 境界だけを ThreadLocal のスコープで囲む。スコープ内の `getPrimaryClip` は 1 item の空の ClipData、`hasPrimaryClip` はその空クリップに対応する true を返す。実データを読まず、システムのクリップボードを書き換えない。スコープ外の入力・コピー照合は元の処理を通す。

READ メソッド全体を skip したり null を返したりしない。特にプレイヤーの READ は null clip だと応答 callback を呼ばない分岐があるため、空の item を持つ clip で元の応答処理を通す。Coroutine は各 `invokeSuspend` を囲み、finally でスコープを解除する。別スレッドにはスコープを伝播させない。今回確認した実際の取得箇所はその呼び出し中に実行される。

既知クラスをシグネチャで確認して初回 attach 時に登録し、フルスキャンではマーカー・API invoke・型で再確認する。Web と RPC は操作／content の分岐を判定して SET を除外する。曖昧な候補は登録せず、5 境界それぞれのスキャン結果を表示する。未知版で改名・処理移動があった場合は、初回の検出や子プロセスのキャッシュ利用に遅延があり得るため再起動と再解析が必要。

上位 APK では解析ツールの `--privacy-baseline` で API・リスナー・ページ境界の差分も取得できる。名前やシグネチャが同じでも、非同期移動、コピー照合との合流、READ / WRITE 条件の変更は Smali で再確認する。

クラウドで Debug APK ビルド、v3 署名検証、JVM 63 件・Python 6 件のテストに成功した。テストは GET / SET、RPC の null / 非 null / 空文字 WRITE、キャッシュ復元、入れ子・例外・別スレッドのスコープ、子プロセス適用を確認する。拡張した解析コマンドも 9.0.0 APK で再実行し、旧ヘルパーとの差分なし、ページ境界 5 種がそれぞれ一意、API 辺・リスナー・境界の基準との差分なしを確認した。LSPosed 実機での動作・Android のアクセス通知・各画面の操作は未検証。

## 実機で確認する組み合わせ

1. 両方 OFF：元の起動判定、コピー、検索／ライブ入力への貼り付け、ページ GET / SET を確認する。
2. 自動監視 ON・ページ OFF：再起動し、外部でコピー → 起動／復帰、前面で変更通知、アプリ内コピー → 検索欄への貼り付けを確認する。ページの独自貼り付けは保持する。
3. 両方 ON：通常入力の貼り付けとコピー成功照合は動作し、Web／Mall／ミニゲーム／プレイヤー READ は空になること、同じ API の SET が引き続き動作することを確認する。
4. 自動監視 OFF・ページ ON：再起動し、自動判定が復帰する一方でページ READ の制限が維持されることを確認する。
