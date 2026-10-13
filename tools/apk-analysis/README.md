# APK のクリップボード解析と版比較

JDK 21、Android SDK Build Tools 37.0.0、リポジトリの Gradle Wrapper を使用する。BBZQ 本体の依存関係には追加しない。解析用 Java ライブラリはこのディレクトリの `lib/` に取得され、Git 管理から除外される。

クラウド環境では先に `source /workspace/.bbzq-tools/env.sh` を実行する。

```bash
cd /workspace/BBZQ
bash ./gradlew -p tools/apk-analysis --no-daemon --max-workers=3 installDecompilers
python3 tools/apk-analysis/analyze.py /path/to/bili.apk \
  --output /workspace/BBZQ-analysis/new-version \
  --baseline docs/apk-analysis/9.0.0-9000200/clipboard.json \
  --privacy-baseline docs/apk-analysis/9.0.0-9000200/privacy-inventory.json
```

APK の署名検証に失敗した場合や DEX の解析に失敗した場合は終了する。すべての DEX を dexlib2 で走査し、クリップボード API・関連クラスへのメソッド参照と関連文字列を記録する。対象チェッククラスは既知のクラス名、または `clipboard.read_enable` と `ClipboardChecker` の両方の文字列で探す。フック候補は、そのクラスに一つだけ存在する `static String(ClipboardManager)` で、実際に `getPrimaryClip()` を呼ぶメソッドに限る。

候補クラスと関連クラスを baksmali で逆アセンブルし、候補クラスを Jadx でもデコンパイルする。対象メソッド名は固定しない。候補なし／複数も解析結果として保存され、対応確認済みとは扱わない。

読み取りヘルパーの直接の呼び出し元は、パッケージ外でも Smali に保存する。9.7.0 ではクリップボード変更通知の Runnable が別パッケージに移動している。[9.7.0 の比較結果](../../docs/apk-analysis/9.7.0-9070300/clipboard.md)を参照。

出力には以下が含まれる。

- `summary.json`: APK SHA-256、署名証明書 SHA-256、package/versionCode/versionName、全 DEX の走査数、読み取りメソッド、呼び出し元、安定した文字列、Smali のメソッド本体ハッシュ。
- `clipboard-index.json`: 全 DEX で確認したクリップボード関連の参照・文字列。呼び出しオフセットは DEX code unit 単位。
- `apk-signature.txt` / `apk-badging.txt`: 検証コマンドの実際の出力。
- `smali/<dex>/...` と `jadx/sources/...`: 逆アセンブルとデコンパイルの根拠。
- `diff.json`: `--baseline` 指定時の追加／削除メソッド、同じシグネチャで変わった本体、フレームワーク読み取り呼び出し元の差異。
- `privacy-inventory.json`: 全クリップボード API の呼び出し箇所と件数、リスナー実装のフィールド型、検索入力欄の親クラス、API 名の反射用文字列、5 種のページ読み取り候補。候補の数を unique / missing / ambiguous として保存する。
- `privacy-diff.json`: `--privacy-baseline` 指定時の API 呼び出し辺・リスナー実装・ページ境界の差異。候補検出だけでは互換性を確定しない。

ハッシュではソース行番号・デバッグ位置を除外する。シグネチャ変更や本体変更は再確認の手掛かりであり、互換性の断定ではない。Jadx が復元できないメソッドは Smali の分岐・invoke を確認する。ネイティブコードや動的生成コードの実行を調べるツールではない。

全 `getPrimaryClip` 呼び出しクラスと検出したページ境界も Smali に保存する。読み取りラッパーの呼び出し元を追う場合は、DEX 型名を一行ずつ書いたファイルを `ClipboardIndex` の第 3 引数に渡す。同じ Java クラスパスを使い、`ClipboardIndex <apk> <incoming.json> <target-types.txt>` と実行する。Jadx の個別確認には `run.py jadx --no-res --no-inline-anonymous --no-inline-methods --no-inline-kotlin-lambda --single-class '<Java class name>' -d <output> <extracted.dex>` を使う。今回の各操作の判定は [9.0.0 のケース解析](../../docs/apk-analysis/9.0.0-9000200/clipboard-cases.md)を参照。

大きな APK と生成した解析データはチェックアウト外に保存する。次の APK は別の出力ディレクトリで解析する。APK やダウンロード URL、認証情報をリポジトリに追加しない。

個別の再確認には `run.py` も使用できる。

```bash
python3 tools/apk-analysis/run.py jadx --version
python3 tools/apk-analysis/run.py baksmali --version
```

Jadx 1.5.3 と baksmali 3.0.9 は JCommander の異なる版に依存するため、ランチャーはそれぞれのクラスパスを分離する。Jadx のキャッシュ・設定ディレクトリも書き込み可能な場所に置く。

差分のメソッド改名・本体変更・位置だけの変更の扱いは `python3 -m unittest discover -s tools/apk-analysis -p 'test_*.py'` で検証する。
