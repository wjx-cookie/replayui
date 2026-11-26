package com.replayui.test;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.replayui.log.DebugLog;
import com.replayui.util.ScreenReflect;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * One-command UI tour, meant to be run WHILE RECORDING in a real playing world.
 *
 * <p>{@code /replayui testui} walks a table of screen types. For each step it
 * clears and places a block in front of the player, puts the test items into
 * hotbar slots 0/1 (replacing whatever was there), opens the screen, then waits
 * for the player to close it before advancing. Nothing here touches the replay
 * codec, the wire format, any mixin, or the playback side: it only drives the
 * vanilla game through server commands and a normal right-click, so Flashback
 * records exactly what a player would have done by hand.
 *
 * <p>One state machine, one table. HUD-only states (potion icons, boss bar,
 * title, scoreboard) have no Screen to wait for; they are just table rows with
 * a hold time. The WAIT_OPEN phase has a single generic timed branch for them,
 * so adding a HUD state never adds a code path. Modded screens need no row and
 * no adapter: they are ordinary container menus and appear automatically.
 *
 * <p>Flashback already records and replays mob effects, boss bars, tab list,
 * scoreboard, titles and the action bar. This driver only *triggers* them so a
 * recording contains them - it never re-implements any of that state.
 *
 * <p>Requires a singleplayer world or LAN host: the block/item/entity setup is
 * executed on the integrated server so the world really changes and the server
 * really opens the container. A client-only fake screen would desync and would
 * not be the state a recording needs.
 */
public final class TestUiDriver implements ClientModInitializer {

    private enum Open { BLOCK, INVENTORY, CREATIVE, GAMEMODE, USE_ITEM, MANUAL }

    private static final class Step {
        final String label;
        final String block;
        final String summon;
        final String[] items;
        final String hint;
        final Open open;
        /** > 0 means a HUD-only step: wait this many ticks, ignore screen state. */
        final int holdTicks;
        final String[] setup;
        final String[] teardown;

        Step(String label, String block, String summon, String[] items, String hint,
             Open open, int holdTicks, String[] setup, String[] teardown) {
            this.label = label;
            this.block = block;
            this.summon = summon;
            this.items = items;
            this.hint = hint;
            this.open = open;
            this.holdTicks = holdTicks;
            this.setup = setup;
            this.teardown = teardown;
        }
    }

    private static Step block(String label, String block, Open open, String... items) {
        return new Step(label, block, null, items, null, open, 0, NONE, NONE);
    }

    private static Step blockHint(String label, String block, String hint, Open open, String... items) {
        return new Step(label, block, null, items, hint, open, 0, NONE, NONE);
    }

    /**
     * Block step with server-side setup/teardown commands, so a screen opens
     * with real content instead of an empty shell. {@code {POS}} expands to the
     * placed block's coordinates; {@code {P}} expands to the player name.
     * Still table data - no new phase and no new opener.
     */
    private static Step blockSetup(String label, String block, String hint, Open open,
                                   String[] setup, String[] teardown, String... items) {
        return new Step(label, block, null, items, hint, open, 0, setup, teardown);
    }

    private static Step custom(String label, String summon, String[] items, String hint, Open open) {
        return new Step(label, null, summon, items, hint, open, 0, NONE, NONE);
    }

    /** HUD-only step: run setup commands, hold, then run teardown commands. */
    private static Step timed(String label, int ticks, String[] setup, String[] teardown) {
        return new Step(label, null, null, NONE, null, Open.MANUAL, ticks, setup, teardown);
    }

    private static final String[] NONE = new String[0];
    private static final String TAG = "replayui_test";

    /** A 5x5 ring around the placed block, on the same Y, as "x1 y z1 x2 y z2". */
    private static String area(BlockPos pos, int radius, int dy) {
        int y = pos.getY() + dy;
        return (pos.getX() - radius) + " " + y + " " + (pos.getZ() - radius)
                + " " + (pos.getX() + radius) + " " + y + " " + (pos.getZ() + radius);
    }

    /**
     * Expands {POS} and {SHELVES} before handing a command to the server.
     * Commands needing a placed block are skipped when there is none, so a
     * broken half-command never reaches Brigadier.
     */
    private static void runExpanded(Minecraft client, String command, BlockPos pos) {
        String c = command.replace("{POS}", pos == null ? "" : xyz(pos));
        if (pos != null) {
            c = c.replace("{SHELVES}", area(pos, 2, 0));
        }
        if (c.contains("{SHELVES}") || c.contains("{POS}")) { return; }
        run(client, c);
    }

    /** Pre-fill a real block container so the screen opens with content. */
    private static String[] slots(String... itemIds) {
        String[] commands = new String[itemIds.length];
        for (int i = 0; i < itemIds.length; i++) {
            commands[i] = "item replace block {POS} container." + i
                    + " with " + itemIds[i];
        }
        return commands;
    }

    /** Ender chest inventory lives on the player, not on a block entity. */
    private static String[] enderSlots(String... itemIds) {
        String[] commands = new String[itemIds.length];
        for (int i = 0; i < itemIds.length; i++) {
            commands[i] = "item replace entity {P} enderchest." + i
                    + " with " + itemIds[i];
        }
        return commands;
    }

    /**
     * Add a state here - never add a new code path. Anything reachable by a
     * block right-click goes in as {@link #block}; anything without a Screen
     * goes in as {@link #timed}; the rest picks an existing opener or waits for
     * the player. {P} expands to the player name and {POS} to the placed
     * block's coordinates and {SHELVES} to a 5x5 ring of air-only bookshelf
     * placement. A screen that must open with real content uses
     * {@link #blockSetup} to pre-fill its container slots; HUD-only states
     * (which have no Screen but do appear on the player's display) use
     * {@link #timed}. Pure block states are NOT here: Flashback replays the
     * world natively and this mod never records them.
     * Up to three hotbar slots are overwritten with test gear. Still table
     * data - no new phase and no new opener.
     */
    private static final Step[] STEPS = {
            custom("背包 Inventory (E)", null,
                    new String[]{"minecraft:diamond"}, null, Open.INVENTORY),
            blockHint("工作台 + 配方书", "crafting_table", "点右上角配方书按钮再关闭", Open.BLOCK,
                    "minecraft:oak_log", "minecraft:oak_planks"),
            blockSetup("箱子 Chest", "chest", null, Open.BLOCK,
                    slots("minecraft:iron_ingot 16", "minecraft:apple 5"),
                    NONE, "minecraft:iron_ingot", "minecraft:apple"),
            blockSetup("末影箱 Ender chest", "ender_chest", null, Open.BLOCK,
                    enderSlots("minecraft:iron_ingot 16", "minecraft:apple 5"),
                    NONE, "minecraft:iron_ingot", "minecraft:apple"),
            blockSetup("潜影盒 Shulker box", "shulker_box", null, Open.BLOCK,
                    slots("minecraft:iron_ingot 16", "minecraft:apple 5"),
                    NONE, "minecraft:iron_ingot"),
            blockSetup("熔炉 Furnace", "furnace", null, Open.BLOCK,
                    slots("minecraft:porkchop 4", "minecraft:coal 8"),
                    NONE, "minecraft:coal", "minecraft:porkchop"),
            blockSetup("高炉 Blast furnace", "blast_furnace", null, Open.BLOCK,
                    slots("minecraft:iron_ore 4", "minecraft:coal 8"),
                    NONE, "minecraft:coal", "minecraft:iron_ore"),
            blockSetup("烟熏炉 Smoker", "smoker", null, Open.BLOCK,
                    slots("minecraft:porkchop 4", "minecraft:coal 8"),
                    NONE, "minecraft:coal", "minecraft:porkchop"),
            blockSetup("酿造台 Brewing stand", "brewing_stand", null, Open.BLOCK,
                    slots("minecraft:potion[minecraft:potion_contents={potion:\"minecraft:water\"}] 1",
                            "minecraft:potion[minecraft:potion_contents={potion:\"minecraft:water\"}] 1",
                            "minecraft:potion[minecraft:potion_contents={potion:\"minecraft:water\"}] 1",
                            "minecraft:nether_wart 1", "minecraft:blaze_powder 1"),
                    NONE, "minecraft:nether_wart", "minecraft:blaze_powder"),
            blockSetup("附魔台 Enchanting", "enchanting_table",
                    "已围好书架并给 30 级；把书和青金石放进附魔台看三行满级提示", Open.BLOCK,
                    new String[]{
                            "experience add {P} 30 levels",
                            // Only air becomes bookshelf: the enchanting table
                            // itself, terrain and any pre-existing blocks stay.
                            "fill {SHELVES} minecraft:bookshelf replace minecraft:air"},
                    new String[]{
                            "fill {SHELVES} minecraft:air replace minecraft:bookshelf",
                            "experience add {P} -30 levels"},
                    "minecraft:lapis_lazuli", "minecraft:book"),
            blockHint("铁砧 Anvil", "anvil", "在改名框打几个字再关闭（测富文本还原）", Open.BLOCK,
                    "minecraft:iron_ingot", "minecraft:iron_sword"),
            block("锻造台 Smithing", "smithing_table", Open.BLOCK,
                    "minecraft:netherite_upgrade_smithing_template",
                    "minecraft:netherite_ingot", "minecraft:diamond_sword"),
            blockSetup("信标 Beacon", "beacon",
                    "已预放钻石；没金字塔时效果按钮灰属正常，要测选择请垫一层 3x3 铁块",
                    Open.BLOCK, slots("minecraft:diamond"), NONE,
                    "minecraft:iron_ingot", "minecraft:diamond"),
            block("切石机 Stonecutter", "stonecutter", Open.BLOCK,
                    "minecraft:stone"),
            block("制图台 Cartography", "cartography_table", Open.BLOCK,
                    "minecraft:filled_map", "minecraft:paper"),
            blockHint("织布机 Loom", "loom", "放旗帜+染料进去看图案列表", Open.BLOCK,
                    "minecraft:white_banner", "minecraft:red_dye"),
            block("砂轮 Grindstone", "grindstone", Open.BLOCK,
                    "minecraft:iron_sword", "minecraft:iron_sword"),
            blockSetup("合成器 Crafter (1.21)", "crafter", null, Open.BLOCK,
                    slots("minecraft:iron_ingot 4"),
                    NONE, "minecraft:iron_ingot"),
            blockHint("讲台 Lectern", "lectern", "放一本书上讲台再翻页", Open.BLOCK,
                    "minecraft:book"),
            blockSetup("发射器 Dispenser", "dispenser", null, Open.BLOCK,
                    slots("minecraft:arrow 16"),
                    NONE, "minecraft:arrow"),
            blockSetup("漏斗 Hopper", "hopper", null, Open.BLOCK,
                    slots("minecraft:iron_ingot 8"),
                    NONE, "minecraft:iron_ingot"),
            custom("村民交易 Villager",
                    "minecraft:villager {NoAI:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:librarian\",level:2,type:\"minecraft:plains\"}}",
                    new String[]{"minecraft:emerald", "minecraft:book"},
                    "右键村民打开交易（已给绿宝石和书）", Open.MANUAL),
            custom("马/羊驼背包 Horse",
                    "minecraft:horse {NoAI:1b,Tags:[\"" + TAG + "\"],Tame:1b,SaddleItem:{id:\"minecraft:saddle\",count:1}}",
                    new String[]{"minecraft:saddle", "minecraft:golden_carrot"},
                    "已驯服带鞍；若 NBT 不生效就手动上鞍再潜行右键", Open.MANUAL),
            custom("箱子矿车 Chest minecart",
                    "minecraft:chest_minecart {Tags:[\"" + TAG + "\"],Items:[{Slot:0b,id:\"minecraft:iron_ingot\",count:16},{Slot:1b,id:\"minecraft:apple\",count:5}]}",
                    NONE, "右键矿车打开（已预放物品）", Open.MANUAL),
            custom("书本与笔 Book & quill", null,
                    new String[]{"minecraft:book_and_quill"}, "写几个字再关闭", Open.USE_ITEM),
            custom("成书 Written book", null,
                    new String[]{"minecraft:written_book[minecraft:written_book_content={title:\"ReplayUI\",author:\"Tester\",pages:['{\"text\":\"ReplayUI test page\"}']}]"},
                    "已预填一页文字；1.21 组件语法，版本不同会 warn 跳过", Open.USE_ITEM),
            blockHint("告示牌 Sign", "oak_sign", "右键编辑并写几个字再关闭", Open.MANUAL,
                    "minecraft:oak_sign"),
            blockHint("命令方块 Command block",
                    "command_block{Command:\"say ReplayUI command block test\"}",
                    "已预填命令，右键打开（需创造权限）", Open.MANUAL,
                    "minecraft:redstone_block"),
            blockHint("结构方块 Structure block",
                    "structure_block{mode:\"SAVE\",name:\"replayui:test\"}",
                    "已预填结构名，右键打开（需创造权限）", Open.MANUAL,
                    "minecraft:redstone_block"),
            custom("创造物品栏 Creative", null, NONE, null, Open.CREATIVE),
            custom("F3+F4 游戏模式切换", null, NONE, null, Open.GAMEMODE),

            /* ---- HUD states. Flashback records these natively; we only trigger them. ---- */
            timed("状态效果 HUD", 100,
                    new String[]{
                            "effect give {P} minecraft:speed 5 2",
                            "effect give {P} minecraft:night_vision 5 0",
                            "effect give {P} minecraft:haste 5 1",
                            "effect give {P} minecraft:jump_boost 5 1"},
                    // Clear only what we gave: the player may have had other
                    // effects before the tour started.
                    new String[]{
                            "effect clear {P} minecraft:speed",
                            "effect clear {P} minecraft:night_vision",
                            "effect clear {P} minecraft:haste",
                            "effect clear {P} minecraft:jump_boost"}),
            timed("Boss 血条", 80,
                    new String[]{
                            // Idempotent if a previous run was interrupted.
                            "bossbar remove replayui:test",
                            "bossbar add replayui:test \"ReplayUI Test Boss\"",
                            "bossbar set replayui:test players {P}",
                            "bossbar set replayui:test value 60",
                            "bossbar set replayui:test color BLUE"},
                    new String[]{"bossbar remove replayui:test"}),
            timed("Title / 副标题 / Actionbar", 80,
                    new String[]{
                            "title {P} times 10 60 10",
                            "title {P} title {\"text\":\"ReplayUI\",\"color\":\"gold\"}",
                            "title {P} subtitle {\"text\":\"UI tour\"}",
                            "title {P} actionbar {\"text\":\"Action bar test\"}"},
                    NONE),
            timed("计分板侧栏", 80,
                    new String[]{
                            "scoreboard objectives remove replayui_test",
                            "scoreboard objectives add replayui_test dummy",
                            "scoreboard objectives setdisplay sidebar replayui_test",
                            "scoreboard players set {P} replayui_test 7"},
                    new String[]{"scoreboard objectives remove replayui_test"}),
    };

    private enum Phase { IDLE, PREPARE, OPEN, WAIT_OPEN, WAIT_CLOSE }

    private static Phase phase = Phase.IDLE;
    private static int index;
    private static int timeout;
    private static BlockPos placed;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommandManager.literal("replayui")
                        .then(ClientCommandManager.literal("testui")
                                .executes(ctx -> { start(Minecraft.getInstance()); return 1; })
                                .then(ClientCommandManager.literal("stop")
                                        .executes(ctx -> { stop(Minecraft.getInstance()); return 1; }))
                                .then(ClientCommandManager.literal("next")
                                        .executes(ctx -> { skip(Minecraft.getInstance()); return 1; }))
                                .then(ClientCommandManager.literal("list")
                                        .executes(ctx -> { list(Minecraft.getInstance()); return 1; })))));

        ClientTickEvents.END_CLIENT_TICK.register(TestUiDriver::tick);
    }

    private static void start(Minecraft client) {
        if (client == null || client.player == null) { return; }
        if (client.getSingleplayerServer() == null) {
            message(client, "ReplayUI testui 需要单人世界或局域网主机（集成服务端）");
            return;
        }
        // Clear any mobs left by an interrupted previous run, without relying
        // on the step index (which may be out of range from that run).
        run(client, "kill @e[tag=" + TAG + "]");
        index = 0;
        timeout = 0;
        placed = null;
        phase = Phase.PREPARE;
        message(client, "ReplayUI UI 巡测开始，共 " + STEPS.length + " 项；关掉界面自动进入下一项");
        DebugLog.info("testui: started, " + STEPS.length + " steps");
    }

    private static void stop(Minecraft client) {
        finishStep(client);
        phase = Phase.IDLE;
        placed = null;
        message(client, "ReplayUI UI 巡测已停止");
        DebugLog.info("testui: stopped");
    }

    private static void skip(Minecraft client) {
        if (phase == Phase.IDLE) { return; }
        if (index + 1 >= STEPS.length) { stop(client); return; }
        finishStep(client);
        index++;
        phase = Phase.PREPARE;
        message(client, "跳到 " + (index + 1) + "/" + STEPS.length + " " + STEPS[index].label);
    }

    private static void list(Minecraft client) {
        StringBuilder sb = new StringBuilder("ReplayUI UI 巡测：");
        for (int i = 0; i < STEPS.length; i++) {
            sb.append(i + 1).append('.').append(STEPS[i].label).append(' ');
        }
        message(client, sb.toString());
    }

    private static void tick(Minecraft client) {
        if (phase == Phase.IDLE || client == null || client.player == null) { return; }
        if (index >= STEPS.length) { stop(client); return; }
        Step step = STEPS[index];
        switch (phase) {
            case PREPARE:
                prepare(client, step);
                phase = Phase.OPEN;
                break;
            case OPEN:
                open(client, step);
                timeout = step.holdTicks > 0 ? step.holdTicks
                        : step.open == Open.MANUAL ? 200 : 60;
                phase = Phase.WAIT_OPEN;
                break;
            case WAIT_OPEN:
                if (step.holdTicks > 0) {
                    // One generic branch covers every HUD-only state: no Screen
                    // to wait for, just hold it long enough to be recorded.
                    if (--timeout <= 0) { advance(client); }
                } else if (client.screen != null) {
                    phase = Phase.WAIT_CLOSE;
                    DebugLog.info("testui: opened step " + (index + 1) + "/" + STEPS.length + " " + step.label);
                } else if (--timeout <= 0) {
                    DebugLog.warn("testui: screen never opened for " + step.label + ", skipping");
                    message(client, "未打开：" + step.label + "，自动跳过");
                    advance(client);
                }
                break;
            case WAIT_CLOSE:
                if (client.screen == null) {
                    DebugLog.verbose("testui: closed " + step.label);
                    advance(client);
                }
                break;
            default:
                break;
        }
    }

    private static void advance(Minecraft client) {
        finishStep(client);
        index++;
        if (index >= STEPS.length) {
            placed = null;
            phase = Phase.IDLE;
            message(client, "ReplayUI UI 巡测完成，可开始回放检查");
            DebugLog.info("testui: complete");
            return;
        }
        phase = Phase.PREPARE;
        Step next = STEPS[index];
        String suffix = next.hint == null ? "" : "（" + next.hint + "）";
        message(client, (index + 1) + "/" + STEPS.length + " " + next.label + suffix);
    }

    /** Undo everything this step put into the world before moving on. */
    private static void finishStep(Minecraft client) {
        if (client == null || index < 0 || index >= STEPS.length) { return; }
        for (String command : STEPS[index].teardown) {
            runExpanded(client, command, placed);
        }
        // NoAI keeps summoned mobs still; the tag makes kill exact, so no other
        // villager or horse in the world is ever touched.
        run(client, "kill @e[tag=" + TAG + "]");
        if (placed != null) {
            run(client, "setblock " + xyz(placed) + " minecraft:air");
            placed = null;
        }
    }

    private static void prepare(Minecraft client, Step step) {
        BlockPos pos = front(client.player);
        // Clear first: the recorded world must never inherit whatever was there.
        run(client, "setblock " + xyz(pos) + " minecraft:air");
        if (step.block != null) {
            run(client, "setblock " + xyz(pos) + " minecraft:" + step.block);
            placed = pos;
        }
        if (step.summon != null) {
            run(client, "summon " + step.summon + " " + xyz(pos));
        }
        for (String command : step.setup) {
            runExpanded(client, command, pos);
        }
        // Smithing needs template + ingot + gear, so three hotbar slots.
        for (int i = 0; i < step.items.length && i < 3; i++) {
            // /item replace overwrites an occupied slot - exactly what the tour wants.
            run(client, "item replace entity {P} hotbar." + i
                    + " with " + step.items[i] + " 1");
        }
        DebugLog.verbose("testui: prepared " + step.label + " at " + xyz(pos));
    }

    private static void open(Minecraft client, Step step) {
        LocalPlayer player = client.player;
        switch (step.open) {
            case BLOCK: {
                BlockPos pos = front(player);
                Direction face = player.getDirection();
                Vec3 hit = Vec3.atCenterOf(pos);
                BlockHitResult result = new BlockHitResult(hit, face.getOpposite(), pos, false);
                client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, result);
                break;
            }
            case INVENTORY:
                client.setScreen(new InventoryScreen(player));
                break;
            case CREATIVE: {
                Screen screen = build("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen", player);
                if (screen != null) { client.setScreen(screen); }
                else { DebugLog.warn("testui: creative screen class not found"); }
                break;
            }
            case GAMEMODE: {
                Screen screen = build("net.minecraft.client.gui.screens.debug.GameModeSwitcherScreen", null);
                if (screen != null) { client.setScreen(screen); }
                else { DebugLog.warn("testui: game mode switcher class not found"); }
                break;
            }
            case USE_ITEM:
                client.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                break;
            case MANUAL:
            default:
                break;
        }
    }

    /** Reflective construction reuses the resolver that already handles remap. */
    private static Screen build(String className, Object arg) {
        Class<?> cls = ScreenReflect.find(className);
        if (cls == null) { return null; }
        Object made = arg == null ? ScreenReflect.construct(cls)
                : ScreenReflect.construct(cls, arg);
        return made instanceof Screen ? (Screen) made : null;
    }

    private static BlockPos front(LocalPlayer player) {
        return player.blockPosition().relative(player.getDirection(), 3);
    }

    private static String xyz(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Server-authoritative command. Brigadier's execute is stable across versions. */
    private static boolean run(Minecraft client, String command) {
        MinecraftServer server = client == null ? null : client.getSingleplayerServer();
        if (server == null) {
            DebugLog.warn("testui: no integrated server, skipped: " + command);
            return false;
        }
        String playerName = client.player == null ? "@p"
                : client.player.getName().getString();
        command = command.replace("{P}", playerName);
        try {
            CommandSourceStack source = server.createCommandSourceStack();
            server.getCommands().getDispatcher().execute(command, source);
            return true;
        } catch (CommandSyntaxException e) {
            DebugLog.warn("testui: command failed: " + command + " -> " + e.getMessage());
            return false;
        } catch (Throwable t) {
            DebugLog.warn("testui: command error: " + command, t);
            return false;
        }
    }

    private static void message(Minecraft client, String text) {
        if (client != null && client.player != null) {
            client.player.displayClientMessage(Component.literal(text), false);
        }
        DebugLog.info("testui: " + text);
    }

    private TestUiDriver() {
    }
}
