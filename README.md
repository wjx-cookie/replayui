# Replay UI（回放界面）

[English](#en) · [中文](#zh)

---

<a id="en"></a>

## Overview

Brings container screens — inventory, chest, crafting table, furnace and so on — back into Flashback replays.

Flashback replays the hotbar and worn armour out of the box, but it cannot show a chest being opened: opening a container takes cooperation from a server, and the "server" in a replay is not real. This mod records the screen state into the replay file while recording, then rebuilds the genuine screen during playback.

Because what gets rebuilt is an actual `AbstractContainerScreen`, modded containers work without being adapted one by one. Anything that behaves like a vanilla container is recordable.

## Already working

These are in and usable right now:

- Container screens — every vanilla one: chest, crafting table, furnace, brewing stand, enchanting table, and the player inventory (E)
- Modded containers, for the same reason: no per-mod support needed
- **Scrubbing, fast-forwarding and jumping all show the right contents.** Recording is state-based rather than event-based, so the screen never drifts out of sync with the timeline
- The cursor-held stack — the item riding on the mouse while a recording drags it around
- Read-only screens: no interaction reaches the replay server, ever
- **Esc** or **E** dismisses a screen, and it stays gone until the recorded state changes
- The viewer's own inventory is put back when a screen closes
- 3D floating panels during playback, if Spatial GUI is installed

This is an early version. See [Not supported](#not-supported) for what is still missing.

## Requirements

- Minecraft (see the version on the release page)
- Fabric Loader + Fabric API
- **Flashback** (required)

Flashback is available on Modrinth and CurseForge — grab it there. (It is not published on Maven, so this mod cannot bundle it automatically; see [Building](#building) if you are compiling from source.)

## Installation

Drop these three files side by side into `mods/`:

- this mod's jar
- Flashback
- Fabric API

Spatial GUI, if you want it, goes in the same folder.

No configuration needed — install and play.

## Usage

**Recording** — start a Flashback recording as usual. Open your inventory, a chest, whatever, and the screen state is captured automatically, items and cursor position included.

**Playback** — open a recorded replay. When the player you are watching opens a container, the screen appears on its own and closes on its own as the recording moves on.

Screens are read-only — nothing you do inside one reaches the replay server, and no packet of any kind is sent.

To get rid of a screen for now, press **Esc** or **E**. It will not pop back up until the recorded state changes.

## Spatial GUI

With Spatial GUI installed, rebuilt screens are picked up as ordinary container screens and rendered as 3D floating panels in the world — visible in third person and with a free camera, with hover and item tooltips working normally.

Without it everything still works, just as a flat 2D screen.

## What works / What does not

### <a id="supported"></a> Supported

- Every vanilla container: chest, crafting table, furnace, brewing stand, enchanting table and the rest
- The player inventory (E)
- Modded containers — anything that behaves like a vanilla one
- **Scrubbing, fast-forwarding and jumping** all show the right contents
- The cursor-held stack
- Interaction inside the screen is blocked; no container packets are sent
- The viewer's own inventory is restored when the screen closes
- Cursor recording can be turned off separately
- Optional Spatial GUI integration: 3D panel with the recorded cursor position

### <a id="not-supported"></a> Not supported

- **Creative inventory** — it is not a container screen and has no menu type
- **Non-container screens**: book and quill, signs, command blocks, structure blocks
- **Sub-tick cursor sampling** (not yet) — the cursor is sampled once per tick, so very fast movement may look steppy
- **In-game config screen** — currently toggled only through JVM arguments
- Containers from mods that are not installed on the machine playing the replay are skipped

## Troubleshooting

First check the replay was recorded **with this mod installed**. Older replays contain no screen data, and there is no way to invent it after the fact.

Then look at the log and search for `replayui`:

```
<game directory>/logs/latest.log
```

Add the JVM argument `-Dreplayui.debug=true` for verbose per-tick output. It is off by default because it floods the log.

## Building

Flashback is not on Maven and is licensed ARR (all rights reserved), so it cannot be redistributed with this project.

Drop the Flashback jar into `libs/` — there are instructions in that folder. The output lands in `build/libs/`.

Opening the project folder in IntelliJ usually handles the Gradle wrapper on its own.

The built jar contains only this mod's own code. `.gitignore` excludes `libs/*.jar` so Flashback is never committed by accident (its ARR licence forbids distribution).

## Notes

A few constraints come from Flashback itself and are enforced at runtime — breaking them causes a crash or silent failure. Each one is commented in the code where it applies, so this list stays short:

- **Replays must be recorded with this mod installed.** Older replays hold no screen data, and it cannot be invented after the fact.
- **Flashback is never bundled.** Its licence (ARR) forbids redistribution, so `libs/*.jar` is git-ignored and the built jar contains only this mod's own code.
- **Screen data is per tick, not per frame.** Recording faster would not help: playback reads one sample per tick, so the extra ones would be thrown away.

## Status

> [!NOTE]
> **This is an early version — test it yourself before trusting a recording to it.**
>
> If you want to check it over:
> 1. Container contents update as the recording moves
> 2. The cursor-held stack appears
> 3. Esc / E dismisses the player inventory
> 4. Your own inventory is intact after leaving a replay
>
> If 1 passes, the whole chain works.

## Version support

Single Minecraft version only. For multiple versions, [Stonecutter](https://stonecutter.kikugie.dev/) is worth a look — one shared source tree with `//? if` for the differences.

This mod only uses Flashback APIs that have been stable for a while, so Flashback updates normally need no code change.

## Licence

MIT

---

<a id="zh"></a>

## 简介

让 Flashback 回放里的容器界面 —— 背包、箱子、工作台、熔炉等等 —— 也能重现。

Flashback 自带就能回放快捷栏和身上的装备，但打不开背包和箱子 —— 因为打开容器需要服务端配合，而回放里的「服务端」是虚拟的。本模组在录制时把界面状态一并存进回放文件，回放时以原版方式把真正的界面重建出来。

因为重建出的是货真价实的 `AbstractContainerScreen`，任何按常规方式工作的模组容器都能被录制，无需逐个适配。

## 已经能用了

以下是已经做好、可以直接用的：

- 容器界面 —— 原版全部：箱子、工作台、熔炉、酿造台、附魔台，以及玩家背包（E）
- 模组容器，出于同样的原因：不需要逐个适配
- **拖进度条、快进、跳转显示的都是正确内容。** 录制是状态式的而非事件式的，所以界面不会和进度条错位
- 手持物 —— 录制时拖在鼠标上的那个物品
- 界面只读：任何操作都不会到达回放服务端
- **Esc** 或 **E** 可关掉界面，录制内容变化前不会再弹出来
- 界面关闭时会还原观看者自己的背包
- 装了 Spatial GUI 的话，回放时是 3D 悬浮面板

目前还是早期版本，还差什么见「尚未支持」。

## 依赖

- Minecraft（见发布页上的版本）
- Fabric Loader + Fabric API
- **Flashback**（必需）

Flashback 在 Modrinth 和 CurseForge 上都能下载。（它不在 Maven 上，所以本模组无法自动附带；从源码编译请看「构建」一节。）

## 安装

把以下三个文件平级放进 `mods/`：

- 本模组的 jar
- Flashback
- Fabric API

Spatial GUI 如果要用，同样平级放进去即可。

不需要任何配置，装上就能用。

## 使用

**录制** —— 正常开启 Flashback 录制。录制期间打开背包或箱子，界面状态会自动被记录，包括里面的物品和鼠标位置。

**回放** —— 打开录制好的回放。当你观看的玩家打开容器时，界面会自动出现，并跟随录制内容自动关闭。

界面是只读的 —— 你在界面里的任何操作都不会到达回放服务端，不会发送任何数据包。

想临时关掉当前界面，按 **Esc** 或 **E** 即可；在录制内容变化之前，它不会自动重新弹出。

## Spatial GUI

装上 Spatial GUI 后，重建出的界面会被它识别为普通容器界面，自动渲染成世界中的 3D 悬浮面板，第三人称和自由运镜下都能看到，鼠标悬停、物品提示等也照常工作。

没装 Spatial GUI 也能正常使用，只是以普通的 2D 界面呈现。

## 已实现 / 尚未支持

### 已实现

- 原版所有走容器协议的界面：箱子、工作台、熔炉、酿造台、附魔台等
- 玩家背包（E 键打开）
- 模组容器 —— 凡是按常规方式工作的都行
- **拖进度条、快进、跳转**显示的都是正确内容
- 手持物
- 回放界面内的操作被拦截，不会向回放服务端发送容器数据包
- 观看者背包在界面关闭后自动还原
- 光标录制可单独关闭
- 可选联动：安装 Spatial GUI 后界面作为 3D 面板呈现，并回放录制时的光标位置

### 尚未支持

- **创造模式物品栏** —— 它不是容器界面，无对应菜单类型
- **非容器屏幕**：书本与笔、告示牌、命令方块、结构方块等
- **光标的子帧采样**（暂不支持）—— 目前每刻只采样一次，光标快速移动时可能不够平滑
- **游戏内配置界面** —— 当前只能通过 JVM 参数开关
- 回放中使用了本机未安装的模组容器时，该界面会被跳过

## 排查

先确认：回放是在装有本模组的情况下录制的。旧回放里没有界面数据，本模组无法凭空补上。

然后看日志，搜索 `replayui`：

```
游戏目录/logs/latest.log
```

加 JVM 参数 `-Dreplayui.debug=true` 可开启逐 tick 的详细输出（默认关闭，因为会刷屏）。

## 构建

Flashback 不在 Maven 上，且为 ARR 许可（保留所有权利），不能随项目分发。

把 Flashback 的 jar 放进 `libs/`（该目录内有说明文件）。产物在 `build/libs/`。

用 IntelliJ 直接打开项目目录通常会自动处理 Wrapper。

产物 jar 只含本模组自身代码。`.gitignore` 已排除 `libs/*.jar`，避免误将 Flashback 提交到仓库（ARR 许可不允许分发）。

## 注意事项

以下几条是 Flashback 本身的限制，违反会导致崩溃或静默失效。代码内每条都有注释说明原因，所以这里只列个概要：

- **回放必须在装有本模组的情况下录制。** 旧回放里没有界面数据，事后无法凭空补上。
- **Flashback 不会被打包进去。** 它的许可（ARR）不允许分发，所以 `libs/*.jar` 已被 git 忽略，产物 jar 只含本模组自身代码。
- **界面数据按刻记录，不是按帧。** 采得更快没有意义：回放端每刻只读一条，多出来的会被丢掉。

## 状态

> [!NOTE]
> **这是早期版本 —— 重要录制请先自己试过再依赖它。**
>
> 想确认的话按这个顺序看：
> 1. 容器内容随录制进度更新
> 2. 鼠标上悬停的物品出现
> 3. Esc / E 能关闭玩家背包
> 4. 退出回放后自己的背包完好
>
> 第 1 项通过即说明整条链路可用。

## 版本适配

目前只支持单个 Minecraft 版本。如需适配多个版本，建议引入 [Stonecutter](https://stonecutter.kikugie.dev/) 共用一套源码，用 `//? if` 处理版本差异。

本模组只依赖 Flashback 中已稳定一段时间的 API，Flashback 更新通常无需改动代码。

## 许可

MIT
