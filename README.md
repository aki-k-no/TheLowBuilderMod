# BuilderMod

Minecraft 1.8.9 のクライアント内でローカル MCP サーバーを動かし、AIエージェントから建築操作を受け取るForge製Modです。

## 実装済み

- Forge 1.8.9クライアントModの起動処理
- Mod起動時の組み込みMCPサーバー自動起動
- Streamable HTTPエンドポイント `http://127.0.0.1:25585/mcp`
- MCP `2026-07-28` の `server/discover` と、従来版の `initialize` に対応
- `ping` と建築・観察・シート参照用の `tools/list`
- 256bitランダムトークンによるBearer認証
- Minecraft内からCodexへワンクリック登録
- Agent由来の処理をMinecraftのメインスレッドへ渡すキュー
- `/buildermod status` と `/buildermod codex connect` クライアントコマンド

建築用MCPツールとMinecraftサーバーへ送るコマンドは、後述の各ツールから利用できます。

## セキュリティ

MCPサーバーは外部インターフェースへ公開せず、IPv4ループバック `127.0.0.1` のみに固定して待ち受けます。また、ブラウザーからのDNS rebindingを避けるため、`Origin` がある場合はローカルOriginだけを許可します。

初回起動時に `.minecraft/config/buildermod-pairing.token` へランダムトークンを生成し、すべてのMCPリクエストでBearer認証を要求します。トークンはチャットやログには表示しません。

## Codexとの接続

ワールドへ入って約6秒後、Codexが未接続ならチャットに `[Connect]` が表示されます。クリックしてMCP接続を登録するか、次のコマンドを実行してください。登録後にブラウザは開きません。

```text
/buildermod codex connect
```

## MinecraftからCodexに依頼する

BuilderModは、ローカルにインストールされたCodex CLIを起動できます。モデルや思考量を指定しなければ、Codex CLI側の既定値を使います。処理はバックグラウンドで実行され、最終回答がMinecraftのチャットに届きます。

```text
/builder ask <message>
```

Codexの進行状況はJSONLイベントからMinecraftのチャットへ転送されます。`/builder status` で経過時間と直近の進行状況を確認できます。

`/builder` または `/builder gui` で専用の会話画面を開けます。画面を閉じても、Minecraftを終了するまでは会話履歴とCodexのセッションが残ります。続けて送ったメッセージは同じセッションで処理されます。`Clear` ボタンで履歴を消し、新しい会話を始められます。

会話画面には書き込み許可の切り替えボタン `Writes: Ask`／`Writes: Auto` があります。初期値の `Writes: Ask` で書き込みが拒否された場合は、会話内の `Approve once` ボタンを押すと、その依頼に限って許可して再試行します。`Writes: Auto` は各依頼でBuilderModの書き込みツールを自動許可します。Codex全体の許可設定は変更しません。

`Image` ボタンで、PNG・JPEG・WebP・GIF画像を次のメッセージへ最大4枚（各20 MiB）添付できます。ブラウザや画面撮影ツールでコピーした画像、エクスプローラーでコピーした画像ファイルは `Ctrl+V` でも添付できます。クリップボードが文字だけなら通常の貼り付けになります。画像は新しい会話でも続きの会話でも使えます。通常の画像ファイルは変更せず、クリップボード内の画像はゲーム終了まで一時PNGとして保存します。添付を取り消すには `X` を押します。

同時に処理できる依頼は1件です。固定の時間制限はありません。処理中は `Send` ボタンが `Cancel` に変わり、チャットからは `/builder cancel` で中止できます。事前に単独版のCodex CLIをインストールしてログインしてください。BuilderModは `BUILDERMOD_CODEX_PATH`、`PATH`、Windowsの標準インストール先を順に探します。Codexデスクトップアプリ内の非公開CLIバイナリは使用しません。

会話画面にはCodexが報告した入力・キャッシュ済み入力・出力トークン数と、セッション累計を表示します。CLIから使用量イベントが届くたびに更新します。

入力欄では `Enter` で改行、`Ctrl+Enter` で送信します。`Ctrl+V` は文字の貼り付け、`Ctrl+Shift+V` はクリップボード画像の添付です。`Copy` は最新の回答、`All` は会話全体をコピーします。


## Codex CLI のインストール（Windows）

`/builder ask` と会話 GUI は、Windows にインストールされた **standalone Codex CLI** を起動します。ChatGPT のデスクトップアプリだけでは不足するため、初回のみ PowerShell で次を実行してください。

```powershell
irm https://chatgpt.com/codex/install.ps1 | iex
```

インストール後、同じ PowerShell でログインし、ブラウザーで ChatGPT アカウントを認証します。

```powershell
codex login
```

次のコマンドで、CLI の版とログイン状態を確認できます。

```powershell
codex --version
codex login status
```

BuilderMod は `BUILDERMOD_CODEX_PATH` が設定されている場合はその実行ファイルを使います。未設定時は `PATH`、続いて標準の Windows インストール先 `%LOCALAPPDATA%\Programs\OpenAI\Codex\bin\codex.exe` を探索します。通常は環境変数の設定は不要です。

### 更新とモデル確認

CLI の更新は次で行えます。

```powershell
codex update
```

自己更新に対応していない場合は、上記のインストールコマンドをもう一度実行してください。更新後は、BuilderMod を起動する前に次で、その CLI が実際に利用できるモデルを確認できます。

```powershell
codex debug models
```

モデルはChatGPTアプリとCLIで常に同じとは限りません。アカウント、プラン、CLIの版、段階的な提供状況によって変わります。BuilderModで選べる候補は後述の「モデルと思考量の切り替え」に記載しています。`debug models` の結果が異なる場合は、画面で `Default` を選んでCLI側の既定モデルを使ってください。選択したモデルが未対応だった場合、BuilderModは既定モデルで同じ依頼を自動的に再試行します。

## Claude Code CLI の導入（Windows）

Claudeを使うには、Minecraftを起動するWindows上に **Claude Code CLI** をインストールし、CLIで初回ログインを済ませます。ClaudeのデスクトップアプリだけではBuilderModからCLIを起動できません。利用できるアカウントやインストール方法は[Claude Code公式クイックスタート](https://code.claude.com/docs/en/quickstart)を参照してください。

PowerShellで公式のネイティブインストーラーを実行します。インストールが終わったら**新しい**PowerShellを開き、版を確認してください。ネイティブ版は自動更新されます。

```powershell
irm https://claude.ai/install.ps1 | iex
claude --version
```

初回は対話モードの `claude` を実行し、表示されるログインや利用規約の案内を完了してください。終了後、認証状態とインストール状態を確認できます。

```powershell
claude
claude auth status
claude doctor
```

`claude` が見つからない場合はPowerShellを開き直し、それでも解決しなければ[公式のPATH設定手順](https://code.claude.com/docs/en/troubleshoot-install#verify-your-path)を確認してください。BuilderModは起動時の `PATH` から `claude.exe` または `claude.cmd` を探します。別の場所にインストールした場合は、Minecraftの起動前に `BUILDERMOD_CLAUDE_PATH` にその実行ファイルの絶対パスを設定できます。Git for WindowsはWindowsでBashツールを使う場合に推奨されますが、CLIのインストールに必須ではありません。詳しくは[公式セットアップ](https://code.claude.com/docs/en/setup)を参照してください。

Minecraft内で `/builder` または `/builder gui` を実行し、GUI左上の `AI: Codex` ボタンを押して `AI: Claude` に切り替えます。簡単なメッセージを送って応答を確認してください。BuilderModは起動時にローカルMCP接続設定を `.minecraft/buildermod-claude-mcp.json` に自動生成し、Claude Codeの起動時に読み込ませます。ペアリングトークンをClaudeのグローバル設定には書き込みません。

GUIの `Model` は `Default → sonnet → opus → haiku` を切り替えます。`Think` はClaude Code側に委ねられます。Claude Codeが部分イベントを出す場合は、思考の短い更新と使用中のツール名を会話GUIに表示します。単純な質問では思考イベントが出ないこともあります。

続きの会話では取得したセッションIDを使って前の発言を引き継ぎます。`Clear` は履歴とセッションIDを消して新しい会話を始めます。Minecraftを終了するとBuilderMod側の会話状態は失われます。`Writes: Ask` で書き込みが拒否された場合は `Approve once` で同じ依頼を一回だけ自動承認として再試行できます。`Writes: Auto` はBuilderMod MCPツールをClaude Codeに許可します。


## TheLow 開発 Wiki

TheLow のコマンド、ダンジョン開発、サーバー固有の機能に関する依頼では、Codex は最初に読み取り専用 MCP ツール `get_thelow_wiki_page` を引数なしで呼び出し、[TheLow 開発用 Wiki](https://wikiwiki.jp/thelow-dev/) のトップページを確認します。コマンドの生成や実行前には、`page` に相対ページ名を指定して該当ページも読み、構文を確認します。例: `command/sequencecommand`。

Wiki は公開情報をその都度取得します。ページは 300 KiB に制限され、`page` は Wiki 内の相対ページ名だけを受け付けます。このツールは読み取り専用で、Minecraft やサーバーへコマンドを送信しません。

## 非公開のGoogleスプレッドシートを参照する

BuilderModはGoogleアカウントのOAuthログインを使い、名前を付けて登録した**複数のスプレッドシート**をMCPツール `list_config_sheets` と `read_config_sheet` から読み取れます。シートの内容はAIへの参考データとして渡され、シートへの書き込みは実装していません。GoogleのOAuth権限は `spreadsheets.readonly` です。この権限自体はアカウントで閲覧できる全スプレッドシートに及びますが、BuilderModのツールは登録されたIDだけを読みます。[Googleの権限の説明](https://developers.google.com/workspace/sheets/api/scopes)

### Google OAuthの初回設定

非公開シートを読むには、利用者ごとにGoogle CloudのOAuthクライアントを作ります。通常のSheets API利用は追加料金なしですが、[利用枠と料金](https://developers.google.com/workspace/sheets/api/limits)を確認してください。以下は個人のGoogleアカウントで自分のシートを読む場合の手順です。[Google Sheets APIの公式クイックスタート](https://developers.google.com/workspace/sheets/api/quickstart/java)

1. [Google Cloud Console](https://console.cloud.google.com/)で新しいプロジェクトを作成し、画面上部でそのプロジェクトが選択されていることを確認します。
2. 「APIとサービス」→「ライブラリ」で **Google Sheets API** を探して有効化します。
3. 「Google Auth Platform」→「Branding」で同意画面の設定を始め、アプリ名（例: `BuilderMod`）、サポート用メール、連絡先メールを入力します。
4. 「Audience」で **External（外部）** を選択します。公開ステータスが **Testing（テスト中）** の場合は、同じ画面の「Test users（テストユーザー）」→「Add users」で、Minecraftからのログイン時に使うGoogleアカウントのメールアドレスを追加して保存します。すでに一覧にある場合は再追加不要です。[同意画面とテストユーザーの公式手順](https://developers.google.com/workspace/guides/configure-oauth-consent)
5. 「Data Access」で読み取り用スコープ `https://www.googleapis.com/auth/spreadsheets.readonly` を設定します。
6. 「Clients」→「Create client」でアプリの種類を **Desktop app（デスクトップアプリ）** にして作成し、クライアントJSONをダウンロードします。Web applicationやAPIキーは使いません。
7. Minecraftで `/builder sheet` を実行し、**OAuth JSON** からダウンロードしたJSONを選びます。`Name` に登録名、`Sheet URL` にシートのURLを入力して **Save** を押します。
8. **Sign in** を押すとブラウザが開きます。手順4のテストユーザーと同じアカウントを選択し、表示された読み取り権限を確認して許可します。ゲーム内画面にログイン完了と出たら接続成功です。複数のシートに対してログインは共通です。

JSONはゲーム内で取り込む代わりに `.minecraft/config/buildermod-google-oauth.json` に置くこともできます。JSONや更新トークンの内容はチャットに貼らないでください。

**403 `access_denied` が出る場合:** 「Audience」のTest users一覧にログイン先アカウントがあるか、ブラウザで別のGoogleアカウントを選んでいないかを確認します。さらに、取り込んだJSONの `client_id` が、テストユーザーを設定した**同じCloudプロジェクト**の「Clients」にあるIDと一致するか確認してください。JSONを取り込み直す場合は `/builder sheet` の **OAuth JSON** を再度使用します。テスト中のOAuth認可と更新トークンは7日で期限切れになるため、その場合は **Sign in** をやり直します。[Googleの公開ステータスと制限](https://support.google.com/cloud/answer/15549945)

`/builder sheet gui` でも同じ設定画面を開けます。登録済みの行をクリックすると編集でき、`Remove` で削除できます。

コマンドだけで設定する場合も、次の操作を使えます。

```text
/builder sheet add commands https://docs.google.com/spreadsheets/d/シートID1/edit
/builder sheet add items https://docs.google.com/spreadsheets/d/シートID2/edit
/builder sheet connect
/builder sheet list
/builder sheet status
```

登録を消すには `/builder sheet remove <名前>` を使います。以前の `/builder sheet set <URL>` も使え、`default` という名前で登録します。登録は `.minecraft/config/buildermod.cfg` の `google_sheets` カテゴリに保存され、以前の単一URL設定も読み込み時に引き継ぎます。再起動後も使う更新トークンは `.minecraft/config/buildermod-google-token.json` に保存されるため、両ファイルを共有しないでください。接続を解除したい場合はMinecraftを終了し、そのトークンファイルを削除します。Google側のアクセス許可もアカウント設定から取り消せます。

AIは `list_config_sheets` で登録名を確認し、`list_tabs` でタブ名と行数・列数を確認できます。`read_config_sheet` は従来どおり `range` に `Settings!A1:D50` のようなA1表記を指定して一括取得します。1ファイルだけ登録されている場合、`sheet` は省略できます。`range` の省略時は先頭タブの `A1:Z200` を読みます。

大きいタブには `read_config_rows` を使います。`tab` に `list_tabs` の正確な名前を指定し、必要なら `columns: "C"` や `columns: "A:D"`、`max_chars: 500`、`headers: true` を指定します。`headers: true` では1行目をキーにした `records` を返します。`next_row` があれば次回の `start_row` に渡して続きから取得します。Google APIの1回の応答が256 KiBを超えると、取得行数を自動で減らします。1行だけで超える場合は列を絞ってください。

`search_sheet` はキーワードまたは `regex: true` の正規表現でセルを探し、タブ名・セル位置・短いプレビューを返します。`next_tab`、`next_row`、`next_column` が返ったら、それぞれ `start_tab`、`start_row`、`start_column` に渡して続きを検索できます。`get_function` は `groupid` と `funcname` の完全一致で `function` タブから1行を探し、ヘッダー名を付けて返します。タブ名が異なる場合は `tab` を指定します。いずれも読み取り専用です。Google Sheets APIのアクセス権エラー時は、URL、同意したGoogleアカウント、Sheets APIの有効化を確認してください。

## Minecraftの状態を調べるMCPツール

現在読み込まれているワールドに対して、次の読み取り専用ツールを使えます。

- `get_player_context`: プレイヤーの位置・視線・ディメンション・ゲームモード・時刻・天候・見ているブロック
- `get_height_map`: 周囲の地形の高さ。必要に応じて上面ブロックや間引き間隔も指定可能（半径32まで）
- `capture_view`: プレイヤーを動かさず、仮想カメラからPNG画像を撮影（初期値320×180、最大512×512）
- `get_blocks`: 直方体内のブロックをパレットと連長圧縮で取得（最大32,768ブロック）
- `inspect_position`: 1座標のブロック・材質・明るさ・空・バイオーム・タイルエンティティ
- `summarize_blocks`: 大きな直方体内のブロック種類別の個数（最大262,144ブロック）

ワールドと描画処理はMinecraftのクライアントスレッドで実行します。ワールドが開いていない場合や必要なチャンクが未読み込みの場合は、エラーを返します。これらのツールはブロック配置やサーバーへのコマンド送信を行いません。

## Minecraftの建築用MCPツール

次の書き込みツールも利用できます。Codexの許可設定が適用されるよう、MCP上では変更を伴うツールとして登録されています。

- `set_block`: Minecraft 1.8.9の `/setblock`。ブロックデータと置換・破壊・保持モードに対応
- `teleport_player`: 現在のプレイヤー、または名前を指定したプレイヤーを絶対座標へ `/tp` で移動。サーバー権限が必要
- `place_command_block`: インパルス型コマンドブロック（ID 137）を内容付きで配置。短文は `/blockdata`、長文は通常のコマンドブロック編集パケットを使用し、サーバーの応答と配置結果を報告。長文にはクリエイティブモード、コマンドブロックの有効化、サーバー権限が必要で、上限はUTF-8で30,000バイト
- `fill_blocks`: `/fill`。通常の32,768ブロック上限、各塗りつぶしモード、置換対象の指定に対応
- `sequence_set_blocks`: 座標を順に指定するTheLowの `/sequence_setblock <seconds> blockId[:data] x y z & ...`
- `fawe_region_edit`: `//pos1`、`//pos2` で選択し、`//set`、`//replace`、`//walls`、`//faces`、`//overlay` を実行（最大200万ブロック）
- `fawe_workflow`: 選択、マスク・変形、地形編集、図形生成、クリップボード操作、移動、バイオーム編集、取り消しなどを最大6手順で実行。通常は最後に全体設定を解除

Codexには、建築時はコマンド数と所要時間を抑えやすいFAWEを優先するよう指示しています。直方体は `fawe_region_edit`、図形・マスク・模様・地形などは `fawe_workflow` を使います。`sequence_set_blocks` は時間指定の配置や、FAWEで表しにくい少数の不規則な座標に限ります。

大きな建築では、最初の書き込み前に必要なワールド情報を集め、範囲・向き・比率・素材・構成・作業順・確認点を内部で決めます。その後、大枠から細部へ進め、節目で結果を確認します。局所的な修正は直接実行します。内部計画は求められない限り会話に表示しません。

素材を選ぶ際は、通常のブロックに加えて階段・ハーフブロック・フェンス・壁・板ガラス・トラップドアなども候補にできます。どこに使うかは建築内容に応じて判断します。

コマンドはログイン中のプレイヤーとして送信します。BuilderModは各項目を検証し、Minecraft 1.8.9のチャットパケット上限100文字を超えるコマンドは、黙って切り詰めずエラーにします。ツールの成功は送信を示すため、サーバー上の反映結果は観察ツールで確認します。

対象サーバーはMinecraft 1.8.9の旧式ブロックIDを使います。Codexには数値IDを既定として指示しています。例: 石は `1`、赤い羊毛はFAWEの模様で `35:14`、重み付きの模様は `70%1,30%4`。新しい名前付きIDは、サーバーで使えることをプレイヤーが明示した場合に使います。

ModがCodexのユーザー設定 `~/.codex/config.toml` にBuilderModを登録します。既存の設定は保持し、変更前のファイルを `config.toml.buildermod.bak` にバックアップします。その後、ブラウザーでローカルセットアップ画面を開きます。

Codexはウィンドウを閉じてもバックグラウンドで動き続けます。セットアップ画面の案内に従い、タスクトレイからCodexを完全終了して開き直してください。画面はMCP接続を自動検出します。

トークンのコピー、環境変数設定、手作業でのTOML編集は不要です。

## 必要環境

- JDK 8（ForgeGradle 2.1 / Gradle 2.7用）
- Minecraft Forge `1.8.9-11.15.1.2318`

## 開発環境

```powershell
.\gradlew.bat setupDecompWorkspace
.\gradlew.bat idea
```

## テストとビルド

```powershell
.\gradlew.bat clean build
```

成果物は `build/libs/buildermod-0.12.7.jar` に生成されます。

## 設定

初回起動後、`.minecraft/config/buildermod.cfg` に設定が生成されます。

- `enabled`: MCPサーバーをMod起動時に自動起動するか。初期値は `true`
- `port`: MCPサーバーの待受ポート。初期値は `25585`

待受アドレスは安全のため設定化せず、常に `127.0.0.1` です。

接続URL:

```text
http://127.0.0.1:25585/mcp
```

## シングルプレイでの一括建築

`local_fill_blocks` は、FAWEやチャットコマンドを使わずにシングルプレイのワールドを直接編集します。1回につき最大65,536ブロックの直方体に対し、置換・保持・輪郭・中空の各モードを使えます。対象チャンクは読み込み済みである必要があります。変更は保存されてクライアントに送られますが、このツールでは取り消せません。通常の書き込み許可設定が適用され、マルチプレイでは使用できません。AIは `get_player_context` の `singleplayer` を見て利用可能か判断できます。

## シングルプレイでのschematic貼り付け

独自の建築物では、Codexが従来形式のgzip圧縮されたAlpha `.schematic` ファイルを `.minecraft/schematics/` 内に作成し、`local_paste_schematic` でそのフォルダーからの相対パスと貼り付け先の `x`、`y`、`z` を指定できます。貼り付け先は構造物の最小座標で、WorldEditの原点・オフセット情報は無視します。配列は `(y * Length + z) * Width + x` の順序で、旧式の数値ブロックIDとメタデータを使います。最大65,536ブロックに対応し、対象チャンクは読み込み済みである必要があります。空気は `include_air` が `true` の場合のみ貼り付けます。エンティティ、タイルエンティティ、およびタイルエンティティが必要なブロックは扱えません。取り消し機能はありません。書き込み許可が必要で、ローカルのシングルプレイでのみ動作します。

## 画像を基準にした建築の流れ

大きな建築では、Codexがプレイヤーの参考画像を使うか、利用環境に画像生成ツールがあればMinecraft風の3D完成イメージを作ります。それをブロックで再現し、節目ごとに `capture_view` の画像と比較して修正します。画像生成が使えない場合はその旨を伝え、内部で設計して進めます。簡単な修正では画像の工程を省きます。

Minecraftから起動するCodex CLIは、`.minecraft/schematics` 内でschematicを作成・検証できるよう `workspace-write` サンドボックスで動作します。指示文ではファイル変更とシェルコマンドをその用途に限りますが、サンドボックス自体は設定された作業領域への書き込みを許すため、ファイルシステムで強制される制限ではありません。画面の `Writes` 切り替えはBuilderModのMCP書き込みツールに適用され、シェルやファイル操作には適用されません。画像生成の可否はインストール済みCodex環境のツールに依存します。

## ゲーム内での完成イメージ表示

Codexが完成イメージを作る場合は、PNGまたはJPEGを `.minecraft/schematics/concepts/` に保存し、回答の最後に `BUILDERMOD_CONCEPT_IMAGE: relative/path.png` という行を付けます。BuilderModは参照先がそのフォルダー内の20 MiB以下の画像であることを検証します。画像は会話履歴の回答直後に表示され、会話と一緒にスクロールできます。クリックすると拡大します。会話を消すかMinecraftを終了するまで表示できます。

## モデルと思考量の切り替え

会話画面の `Model` と `Think` ボタンで設定を切り替えます。モデルは `Default`、`gpt-6-astra`、`gpt-6.1-sol`、`gpt-6-sol`、`gpt-6-luna`、`gpt-5.6-sol`、`gpt-5.6-terra`、`gpt-5.6-luna` の順に切り替わります。思考量は `Default`、`low`、`medium`、`high`、`xhigh`、`max`、`ultra` です。値は `buildermod.cfg` に保存され、次のCodex CLIへの依頼から適用されます。`Default` を選ぶとCodex CLI側の既定値を使います。
