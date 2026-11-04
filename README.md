# BuilderMod

Minecraft 1.8.9 のクライアント内でローカル MCP サーバーを動かし、AI Agentから建築操作を受け取るためのForge Modです。

## 実装済み

- Forge 1.8.9クライアントModの起動処理
- Mod起動時の組み込みMCPサーバー自動起動
- Streamable HTTPエンドポイント `http://127.0.0.1:25585/mcp`
- MCP `2026-07-28` の `server/discover` と、従来版の `initialize` に対応
- `ping` と空の `tools/list`
- 256bitランダムトークンによるBearer認証
- Minecraft内からCodexへワンクリック登録
- Agent由来の処理をMinecraftのメインスレッドへ渡すキュー
- `/buildermod status` と `/buildermod codex connect` クライアントコマンド

建築用MCPツールと、Minecraftサーバーへ送るコマンドAPIはまだ登録していません。

## セキュリティ

MCPサーバーは外部インターフェースへ公開せず、IPv4ループバック `127.0.0.1` のみに固定して待ち受けます。また、ブラウザーからのDNS rebindingを避けるため、`Origin` がある場合はローカルOriginだけを許可します。

初回起動時に `.minecraft/config/buildermod-pairing.token` へランダムトークンを生成し、すべてのMCPリクエストでBearer認証を要求します。トークンはチャットやログには表示しません。

## Codexとの接続

ワールドへ入って約6秒後、Codexが未接続ならチャットに `[Connect]` が表示されます。クリックしてMCP接続を登録するか、次のコマンドを実行してください。登録後にブラウザは開きません。

```text
/buildermod codex connect
```

## Ask Codex from Minecraft

BuilderMod can run the locally installed Codex CLI without overriding the user's default model or reasoning effort. The request runs on a background thread and the final reply is returned to Minecraft chat.

```text
/builder ask <message>
```

Codex JSONL progress events are forwarded to Minecraft chat. Use `/builder status` at any time to see the elapsed time and most recent progress event.

Run `/builder` or `/builder gui` to open the dedicated conversation screen. Its in-memory history and Codex session survive closing and reopening the screen until Minecraft exits. Follow-up messages resume the same Codex session, so Codex retains the conversation context. Press `Clear` to discard the history and start a new session.

The GUI has a persistent `Writes: Ask` / `Writes: Auto` switch. `Writes: Ask` is the default. Because `codex exec` is non-interactive, a blocked BuilderMod write is shown in the conversation and exposes an `Approve once` button; pressing it retries the same request in the same session with approval for that turn only. `Writes: Auto` approves BuilderMod write tools for each GUI/command turn without changing the user's global Codex approval policy.

Use the GUI's `Image` button to attach up to four PNG, JPEG, WebP, or GIF files (20 MiB each) to the next message. Press `Ctrl+V` to attach image pixels copied from a browser or screenshot tool, or image files copied in Explorer. If the clipboard only contains text, `Ctrl+V` keeps its normal text-paste behavior. Images work for both a new Codex session and follow-up turns. The selected paths are passed as repeated `codex exec --image` arguments; regular image files are not copied or modified by BuilderMod, while clipboard pixels are saved as temporary PNG files for the game session. The `X` button removes pending attachments.

Only one request runs at a time. Requests have no fixed timeout, so long-running Goal and building work can continue until Codex completes. While a request is running, the GUI's `Send` button changes to `Cancel`; `/builder cancel` provides the same operation from chat. The supported standalone Codex CLI must already be installed and signed in. BuilderMod checks `BUILDERMOD_CODEX_PATH`, the inherited `PATH`, and the official Windows standalone locations under `%LOCALAPPDATA%\\Programs\\OpenAI\\Codex` and `$CODEX_HOME\\packages\\standalone`. BuilderMod never copies or executes the private CLI binary inside the Windows Codex app package.

The GUI shows Codex token usage reported by JSON events: input tokens, cached input tokens, output tokens, and the accumulated session total. Codex CLI normally reports authoritative usage when a turn completes, so the display updates as soon as each usage event arrives rather than estimating tokens locally.

The conversation view uses a multiline editor. Press `Enter` to insert a newline and `Ctrl+Enter` to send. `Ctrl+V` pastes text; `Ctrl+Shift+V` attaches an image from the clipboard. The `Copy` button copies the latest Codex reply, while `All` copies the full in-memory conversation transcript.


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

モデルは ChatGPT アプリと CLI で常に同じとは限りません。アカウント、プラン、CLI の版、段階的な提供状況によって変わります。BuilderMod に現在登録されている候補は `gpt-5.6-sol`、`gpt-5.6-terra`、`gpt-5.6-luna` です。`debug models` の結果が異なる場合は、Default を選んで CLI 側の既定モデルを使ってください。選択したモデルが未対応だった場合、BuilderMod は Default を指定して同じ依頼を自動的に再試行します。

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

## Minecraft MCP observation tools

BuilderMod exposes six read-only tools for the currently loaded client world:

- `get_player_context`: player position/view, dimension, game mode, time, weather, and targeted block
- `get_height_map`: adaptively raw/RLE terrain heights; optional top blocks and sampling (radius up to 32)
- `capture_view`: PNG rendered from a virtual camera without moving the player (320x180 default; up to 512x512 / 262144 pixels)
- `get_blocks`: exact cuboid data encoded as a palette and RLE, with a configurable run cap (up to 32768 blocks)
- `inspect_position`: block, material, light, sky, biome, and tile-entity details at one position
- `summarize_blocks`: block-state counts for a larger cuboid (up to 262144 blocks)

All world and OpenGL access is transferred to Minecraft's client thread. Requests fail explicitly when no world is open or required chunks are not loaded. These observation tools do not place blocks or send server commands.

## Minecraft MCP building tools

BuilderMod also exposes structured write tools. They are marked as destructive, non-read-only MCP tools so Codex can apply write approval policy:

- `set_block`: validated Minecraft 1.8.9 `/setblock` with block data and replace/destroy/keep mode
- `place_command_block`: place an impulse command block (ID 137) and write its command text through `/blockdata`
- `fill_blocks`: validated `/fill` with the vanilla 32768-block limit, fill modes, and optional replace filter
- `sequence_set_blocks`: TheLow `/sequence_setblock <seconds> blockId[:data] x y z & ...` using ordered coordinates
- `fawe_region_edit`: bounded FAWE cuboid edit using `//pos1`, `//pos2`, then `//set`, `//replace`, `//walls`, `//faces`, or `//overlay` (up to 2,000,000 blocks)
- `fawe_workflow`: up to six validated FAWE steps combining selections, global masks/transforms, region/terrain edits, generated shapes, clipboard copy/cut/paste/rotate/flip, movement, biome edits, and undo/redo. Global state is cleared afterward by default.

Codex is instructed to prefer FAWE for building because it usually needs fewer tool calls, commands, tokens, and less time. `fawe_region_edit` is the default for cuboids and `fawe_workflow` for shapes, masks, patterns, terrain, and coordinated edits. `sequence_set_blocks` is reserved for explicitly requested timed placement or small sparse irregular coordinate lists that FAWE cannot reasonably express.

Larger builds use a planning-first flow. Before the first write, Codex gathers the minimum necessary world context and internally settles the footprint, orientation, proportions, palette, components, FAWE operations, execution order, and verification milestones. It then builds coarse-to-fine and checks the world at meaningful stages. A single localized correction or simple isolated edit skips this planning overhead and executes directly. The internal plan is not narrated unless requested, keeping token use down.

When selecting blocks, Codex is reminded that stairs, slabs, fences, walls, panes, trapdoors, and other non-full blocks are available alongside full blocks. This is deliberately only an optional design possibility: it does not prescribe the structure, decoration style, palette, or where those blocks must be used.

Commands are sent as the logged-in player on Minecraft's client thread. BuilderMod validates every field and rejects generated commands over Minecraft 1.8.9's 100-character chat-packet limit instead of allowing silent truncation. A successful tool result confirms transmission only; use the observation tools afterward to verify that the server accepted and applied the change.

The target server uses Minecraft 1.8.9 legacy block IDs. Codex is instructed to use numeric IDs by default: `1` for stone, `35:14` for red wool in FAWE patterns, and weighted patterns such as `70%1,30%4`. Modern or namespaced block names are avoided unless the player explicitly confirms that the server accepts them.

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

## Singleplayer bulk building

`local_fill_blocks` directly edits the integrated server world in singleplayer, without FAWE or chat commands. It supports replace, keep, outline, and hollow modes for cuboids up to 65,536 blocks per call. The target chunks must already be loaded. Changes are saved and sent to clients, and cannot be undone through this tool. It is a write tool subject to the usual approval setting; it rejects multiplayer worlds. `get_player_context` reports `singleplayer` so an agent can choose the appropriate tool.

## Singleplayer schematic paste

For custom structures, Codex can create a classic gzip-compressed Alpha `.schematic` under `.minecraft/schematics/` and call `local_paste_schematic` with a path relative to that directory and destination `x`, `y`, `z`. The destination is the minimum corner; WorldEdit origin/offset tags are ignored. The classic arrays use `(y * Length + z) * Width + x` order and legacy numeric block IDs and metadata. Up to 65,536 blocks are supported; target chunks must be loaded. Air is skipped unless `include_air` is true. Entities, tile entities, and blocks requiring tile entities are rejected. There is no undo. This is an approval-controlled write and only works in local singleplayer.

## Visual building workflow

For substantial builds, Codex uses an attached reference or, when an image-generation tool is available in its environment, generates a Minecraft-style 3D concept before building. It translates that visual target into blocks, then uses `capture_view` at meaningful milestones to compare and correct the build. If image generation is unavailable it reports the limitation and proceeds with an internal design. Simple edits skip the concept step.

The Minecraft-launched Codex CLI runs with the `workspace-write` sandbox so it can create and validate schematics under `.minecraft/schematics`. The prompt limits file changes and shell commands to that purpose; the sandbox itself permits writes across its configured workspace, so this scope is an instruction rather than a filesystem-enforced restriction. The existing Writes switch controls BuilderMod MCP writes, not shell or file operations. Image generation depends on tools available to the installed Codex environment.

## In-game concept images

When Codex generates a concept image, it saves a PNG or JPEG under `.minecraft/schematics/concepts/` and finishes its reply with `BUILDERMOD_CONCEPT_IMAGE: relative/path.png`. BuilderMod validates that the referenced file stays in that directory and is an image no larger than 20 MiB. The image appears directly after Codex's reply in the conversation history and scrolls with it; click it to enlarge. The image remains available until the conversation is cleared or Minecraft exits.

## Model and reasoning controls

The BuilderMod conversation screen has `Model` and `Think` buttons. Each click cycles the model between Default, `gpt-6-astra`, `gpt-6.1-sol`, `gpt-6-sol`, `gpt-6-luna`, `gpt-5.6-sol`, `gpt-5.6-terra`, and `gpt-5.6-luna`; reasoning cycles Default, low, medium, high, xhigh, max, and ultra. The values persist in `buildermod.cfg` and are passed to the next Codex CLI request. Choose Default to retain the Codex CLI's own configured model or reasoning effort.
