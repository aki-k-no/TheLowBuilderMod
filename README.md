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

Only one request runs at a time, and each request times out after three minutes. The supported standalone Codex CLI must already be installed and signed in. BuilderMod checks `BUILDERMOD_CODEX_PATH`, the inherited `PATH`, and the official Windows standalone locations under `%LOCALAPPDATA%\\Programs\\OpenAI\\Codex` and `$CODEX_HOME\\packages\\standalone`. BuilderMod never copies or executes the private CLI binary inside the Windows Codex app package.

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
- `fill_blocks`: validated `/fill` with the vanilla 32768-block limit, fill modes, and optional replace filter
- `sequence_set_blocks`: TheLow `/sequence_setblock <seconds> blockId[:data] x y z & ...` using ordered coordinates
- `fawe_region_edit`: bounded FAWE cuboid edit using `//pos1`, `//pos2`, then `//set`, `//replace`, `//walls`, `//faces`, or `//overlay` (up to 2,000,000 blocks)
- `fawe_workflow`: up to six validated FAWE steps combining selections, global masks/transforms, region/terrain edits, generated shapes, clipboard copy/cut/paste/rotate/flip, movement, biome edits, and undo/redo. Global state is cleared afterward by default.

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

成果物は `build/libs/buildermod-0.3.1.jar` に生成されます。

## 設定

初回起動後、`.minecraft/config/buildermod.cfg` に設定が生成されます。

- `enabled`: MCPサーバーをMod起動時に自動起動するか。初期値は `true`
- `port`: MCPサーバーの待受ポート。初期値は `25585`

待受アドレスは安全のため設定化せず、常に `127.0.0.1` です。

接続URL:

```text
http://127.0.0.1:25585/mcp
```
