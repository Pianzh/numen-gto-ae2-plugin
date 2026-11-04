package dev.local.numenae2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.ServerCall;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code ae2}: read and work an Applied Energistics 2 ME network.
 *
 * <p>The read side answers straight from the grid's own storage inventory
 * ({@code IStorageService#getInventory()}), never through a screen, so virtual
 * terminal slots are irrelevant. Writes ({@link #take}, {@link #put}) go through
 * {@code ServerCall#authorize} - the same owner consent popup as breaking a block.
 *
 * <p>A grid is found from any AE2 machine ({@code IActionHost}) whose block entity is
 * loaded: a drive, a pattern provider, a controller, a cell workbench... The Lua side can
 * name one with {@code pos = "x,y,z"}; without it, loaded chunks around her are swept
 * and the nearest machine wins.
 */
public final class Ae2Api {

    private Ae2Api() {
    }

    static void install(NumenApi numen) {
        numen.api("ae2", "ME 网络（应用能源2）：看网格里有什么、按名搜物、查数，经主人点头后取物与存物。", Ae2Api.class);
    }

    // ------------------------------------------------------------------ helpers

    private static ServerLevel level(ServerCall call) {
        return (ServerLevel) call.her().m_9236_();
    }

    private static BlockPos pos3(String s) {
        String[] p = s.trim().split(",");
        if (p.length != 3) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "坐标读不懂：" + s,
                    "pos 写成 \"x,y,z\" 三个整数，例如 \"-86,64,-121\"。");
        }
        try {
            return new BlockPos(Integer.parseInt(p[0].trim()),
                    Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (NumberFormatException e) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "坐标读不懂：" + s,
                    "pos 写成三个整数，例如 \"-86,64,-121\"。");
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String idOf(AEKey key) {
        try {
            if (AEItemKey.is(key)) {
                Item item = ((AEItemKey) key).item;
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(item);
                return rl == null ? item.toString() : rl.toString();
            }
            if (key instanceof AEFluidKey fluid) {
                ResourceLocation rl = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
                return rl == null ? fluid.toString() : (rl + " (fluid)");
            }
        } catch (Throwable ignored) {
        }
        return key.getClass().getSimpleName();
    }

    private static long countOf(MEStorage inv, AEKey key) {
        return inv.getAvailableStacks().get(key);
    }

    private static Item itemFor(String id) {
        try {
            ResourceLocation rl = new ResourceLocation(id);
            if (ForgeRegistries.ITEMS.containsKey(rl)) {
                return ForgeRegistries.ITEMS.getValue(rl);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** The grid behind one AE2 machine, or null if that cell holds no powered, formed machine. */
    private static IGrid gridAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.m_7702_(pos);
        if (be instanceof IActionHost host) {
            try {
                if (host.getActionableNode() != null && host.getActionableNode().getGrid() != null) {
                    return host.getActionableNode().getGrid();
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private record Access(IGrid grid, BlockPos anchor, String via) {
    }

    /** Resolve the grid: named pos first, else the nearest AE2 machine in loaded chunks around her. */
    private static Access access(ServerCall call, Optional<String> posOpt) {
        ServerLevel level = level(call);
        if (posOpt != null && posOpt.isPresent() && !posOpt.get().trim().isEmpty()) {
            BlockPos pos = pos3(posOpt.get());
            IGrid grid = gridAt(level, pos);
            if (grid != null) {
                return new Access(grid, pos, "AE2 设备 @" + pos.m_123341_() + "," + pos.m_123342_() + "," + pos.m_123343_());
            }
            throw new ApiError(ErrorKind.NOT_FOUND,
                    pos.m_123341_() + "," + pos.m_123342_() + "," + pos.m_123343_() + " 处没有接通 ME 网络的 AE2 设备",
                    "那格必须是已成形、已供能的 AE2 机器（驱动、样板供应器、控制器、合成终端都行）；或者不带 pos 让她自动找。");
        }
        Access best = nearestHost(level, call.her().m_20183_(), 2);
        if (best == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "她身边（±2 区块）没有接通 ME 网络的 AE2 设备",
                    "用 ae2.drives() 找到设备坐标，然后带 pos = \"x,y,z\" 点名。");
        }
        return best;
    }

    private static BlockPos anchorOf(ServerCall call, Optional<String> posOpt) {
        if (posOpt != null && posOpt.isPresent() && !posOpt.get().trim().isEmpty()) {
            return pos3(posOpt.get());
        }
        return call.her().m_20183_();
    }

    /** Sweep loaded chunks {@code chunkRadius} around her for the nearest AE2 machine on a grid. */
    private static Access nearestHost(ServerLevel level, BlockPos center, int chunkRadius) {
        int ccx = center.m_123341_() >> 4;
        int ccz = center.m_123343_() >> 4;
        Access best = null;
        double bestDist = Double.MAX_VALUE;
        for (int cx = ccx - chunkRadius; cx <= ccx + chunkRadius; cx++) {
            for (int cz = ccz - chunkRadius; cz <= ccz + chunkRadius; cz++) {
                if (!level.m_7232_(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                for (var entry : chunk.m_62954_().entrySet()) {
                    BlockEntity be = entry.getValue();
                    if (!(be instanceof IActionHost host)) {
                        continue;
                    }
                    IGrid grid;
                    try {
                        if (host.getActionableNode() == null || host.getActionableNode().getGrid() == null) {
                            continue;
                        }
                        grid = host.getActionableNode().getGrid();
                    } catch (Throwable ignored) {
                        continue;
                    }
                    BlockPos pos = entry.getKey();
                    double dist = pos.m_123331_(center);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new Access(grid, pos,
                                "AE2 设备 @" + pos.m_123341_() + "," + pos.m_123342_() + "," + pos.m_123343_());
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ listing

    public record NetOpt(Optional<String> pos) {
    }

    public record NetInfo(String via, int kinds, long items, int nodes, String line) {
    }

    @Fn("她身边（或 pos 点名的设备所连的）ME 网络现在装着多少种、多少件东西。")
    @Example("ae2.net.here()")
    @Example("ae2.net.here({pos = \"-86,64,-121\"})")
    @Note("pos 给任意一台已成形供能的 AE2 机器（驱动/样板供应器/控制器/终端都可以）。")
    public static NetInfo here(ServerCall call, NetOpt args) {
        Access via = access(call, args == null ? null : args.pos());
        KeyCounter list = via.grid().getStorageService().getInventory().getAvailableStacks();
        long items = 0;
        int kinds = 0;
        for (var e : list) {
            kinds++;
            items += e.getLongValue();
        }
        int nodes = via.grid().size();
        return new NetInfo(via.via(), kinds, items, nodes,
                "ME 网络（" + via.via() + "）：网格 " + nodes + " 个节点，存着 " + kinds + " 种、共 " + items + " 件");
    }

    @Fn("列出她身边已加载区块里所有接通 ME 网络的 AE2 设备：坐标和距离，拿去点 pos。")
    @Example("ae2.drives()")
    @Note("扫她周围 ±2 区块；远处的设备区块没加载就看不到。")
    public static Hosts drives(ServerCall call) {
        ServerLevel level = level(call);
        BlockPos center = call.her().m_20183_();
        List<Host> out = new ArrayList<>();
        int ccx = center.m_123341_() >> 4;
        int ccz = center.m_123343_() >> 4;
        for (int cx = ccx - 2; cx <= ccx + 2; cx++) {
            for (int cz = ccz - 2; cz <= ccz + 2; cz++) {
                if (!level.m_7232_(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                for (var entry : chunk.m_62954_().entrySet()) {
                    if (!(entry.getValue() instanceof IActionHost host)) {
                        continue;
                    }
                    try {
                        if (host.getActionableNode() == null || host.getActionableNode().getGrid() == null) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        continue;
                    }
                    BlockPos p = entry.getKey();
                    out.add(new Host(p.m_123341_() + "," + p.m_123342_() + "," + p.m_123343_(),
                            (int) Math.sqrt(p.m_123331_(center))));
                }
            }
        }
        out.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        if (out.size() > 24) {
            out = out.subList(0, 24);
        }
        String line = out.isEmpty() ? "她身边 ±2 区块没有接通 ME 网络的 AE2 设备"
                : "找到 " + out.size() + " 台 AE2 设备（近的在前）：" + out.stream().map(Host::pos).limit(8).toList();
        return new Hosts(out, line);
    }

    public record Host(String pos, int distance) {
    }

    public record Hosts(List<Host> hosts, String line) {
    }

    // ------------------------------------------------------------------ count / find

    public record CountOpt(Optional<String> id, Optional<String> pos) {
    }

    public record Count(String id, long count, String via, String line) {
    }

    @Fn("ME 网络里有多少某个东西。")
    @Example("ae2.net.count({id = \"gtceu:steel_ingot\"})")
    @Example("ae2.net.count({id = \"minecraft:iron_ingot\", pos = \"-86,64,-121\"})")
    @Note("id 写全：命名空间:名字。流体也能数，结果带 (fluid) 标记。")
    public static Count count(ServerCall call, CountOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().orElse("").trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说数什么",
                    "写成 ae2.net.count({id = \"gtceu:steel_ingot\"})。");
        }
        Access via = access(call, args == null ? null : args.pos());
        MEStorage inv = via.grid().getStorageService().getInventory();
        long n = 0;
        boolean hit = false;
        for (var e : inv.getAvailableStacks()) {
            if (idOf(e.getKey()).equalsIgnoreCase(id)) {
                n = e.getLongValue();
                hit = true;
                break;
            }
        }
        if (!hit) {
            throw new ApiError(ErrorKind.NOT_FOUND, "ME 网络里没有 " + id,
                    "用 ae2.net.find({query = \"片段\"}) 搜，或先 ae2.drives() 确认网格。");
        }
        return new Count(id, n, via.via(), "ME 网络里有 " + n + " 个 " + id + "（" + via.via() + "）");
    }

    public record QueryOpt(Optional<String> query, Optional<Integer> limit, Optional<String> pos) {
    }

    public record Row(String id, long count, String kind) {
    }

    public record Found(List<Row> hits, int totalKinds, String line) {
    }

    @Fn("在 ME 网络里按名字找东西：id 片段、英文名片段都认。")
    @Example("ae2.net.find({query = \"steel\"})")
    @Example("ae2.net.find({query = \"ingot\", {limit = 20}})")
    @Note("不区分大小写；kind 恒返回 item 或 fluid，按数量从多到少排。")
    public static Found find(ServerCall call, QueryOpt args) {
        String query = args == null || args.query() == null ? "" : args.query().orElse("").trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说搜什么",
                    "写成 ae2.net.find({query = \"steel\"})。");
        }
        int limit = args == null || args.limit().isEmpty() ? 20 : clamp(args.limit().get(), 1, 100);
        Access via = access(call, args == null ? null : args.pos());
        List<Row> hits = new ArrayList<>();
        int total = 0;
        for (var e : via.grid().getStorageService().getInventory().getAvailableStacks()) {
            total++;
            String id = idOf(e.getKey());
            if (!id.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            hits.add(new Row(id, e.getLongValue(), e.getKey() instanceof AEItemKey ? "item" : "other"));
        }
        hits.sort((a, b) -> Long.compare(b.count(), a.count()));
        List<Row> shown = hits.size() > limit ? hits.subList(0, limit) : hits;
        String line;
        if (hits.isEmpty()) {
            line = "ME 网络里没有名字带「" + query + "」的东西（共扫了 " + total + " 种）";
        } else {
            StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 种");
            if (shown.size() < hits.size()) {
                sb.append("（只列前 ").append(shown.size()).append(" 种）");
            }
            for (Row r : shown) {
                sb.append("\n").append(r.id()).append(" x").append(r.count());
            }
            line = sb.toString();
        }
        return new Found(shown, total, line);
    }

    // ------------------------------------------------------------------ moving goods

    public record MoveOpt(Optional<String> id, Optional<Integer> count, Optional<String> pos) {
    }

    public record Move(String id, long wanted, long moved, String via, String line) {
    }

    private static final int MAX_COUNT = 1024;

    @Fn("从 ME 网络里取东西放进她的背包——先问主人，点头才动手。")
    @Example("ae2.net.take({id = \"gtceu:steel_ingot\", count = 32})")
    @Note("一次最多 " + MAX_COUNT + " 个；背包放不下的部分会退回网络并明说。")
    public static Pending<Move> take(ServerCall call, MoveOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().orElse("").trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说取什么",
                    "写成 ae2.net.take({id = \"gtceu:steel_ingot\", count = 32})。");
        }
        int want = args == null || args.count().isEmpty() ? 1 : clamp(args.count().get(), 1, MAX_COUNT);
        ServerLevel level = level(call);
        Item item = itemFor(id);
        if (item == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品：" + id,
                    "id 写成 gtceu:steel_ingot 这样：命名空间:名字。");
        }
        Access via = access(call, args == null ? null : args.pos());
        MEStorage inv = via.grid().getStorageService().getInventory();
        AEItemKey key = AEItemKey.of(new ItemStack(item));
        long have = countOf(inv, key);
        if (have <= 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "ME 网络里没有 " + id,
                    "先用 ae2.net.find({query = \"片段\"}) 看看网络里有什么。");
        }
        long n = Math.min(have, want);
        BlockPos anchor = via.anchor();
        return call.authorize(Action.take(anchor, level.m_8055_(anchor), item)).then(ignored -> {
            long got = inv.extract(key, n, Actionable.MODULATE, IActionSource.empty());
            if (got <= 0) {
                throw new ApiError(ErrorKind.FAILED, "网络没给出来 " + id,
                        "刚好被别的机器抽走了？再试一次。");
            }
            int asInt = (int) Math.min(got, Integer.MAX_VALUE);
            ItemStack stack = key.toStack(asInt);
            Inventory bag = call.her().m_150109_();
            if (!bag.m_36054_(stack)) {
                inv.insert(key, stack.m_41613_(), Actionable.MODULATE, IActionSource.empty());
                throw new ApiError(ErrorKind.FAILED, "背包塞不下 " + id + "，东西已退回网络",
                        "先腾出背包空格再取。");
            }
            return new Move(id, n, asInt, via.via(),
                    "从 ME 网络取了 " + asInt + " 个 " + id + " 进背包（" + via.via() + "）");
        });
    }

    @Fn("把她背包里的东西存进 ME 网络——先问主人，点头才动手。")
    @Example("ae2.net.put({id = \"minecraft:cobblestone\", count = 64})")
    @Note("一次最多 " + MAX_COUNT + " 个；网络收不下的会留在背包里。")
    public static Pending<Move> put(ServerCall call, MoveOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().orElse("").trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说存什么",
                    "写成 ae2.net.put({id = \"minecraft:cobblestone\", count = 64})。");
        }
        int want = args == null || args.count().isEmpty() ? 1 : clamp(args.count().get(), 1, MAX_COUNT);
        ServerLevel level = level(call);
        Item item = itemFor(id);
        if (item == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品：" + id,
                    "id 写成 gtceu:steel_ingot 这样：命名空间:名字。");
        }
        Access via = access(call, args == null ? null : args.pos());
        MEStorage inv = via.grid().getStorageService().getInventory();
        AEItemKey key = AEItemKey.of(new ItemStack(item));
        Inventory bag = call.her().m_150109_();
        int carried = 0;
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (!stack.m_41619_() && stack.m_41720_() == item) {
                carried += stack.m_41613_();
            }
        }
        if (carried <= 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她身上没有 " + id,
                    "先 ae2.net.take 取出来，或者确认背包里确实有。");
        }
        int n = Math.min(carried, want);
        return call.authorize(Action.drop(item)).then(ignored -> {
            int moving = n;
            long moved = 0;
            for (int i = 0; i < bag.m_6643_() && moving > 0; i++) {
                ItemStack stack = bag.m_8020_(i);
                if (stack.m_41619_() || stack.m_41720_() != item) {
                    continue;
                }
                int give = Math.min(stack.m_41613_(), moving);
                long accepted = inv.insert(key, give, Actionable.SIMULATE, IActionSource.empty());
                if (accepted <= 0) {
                    continue;
                }
                long real = inv.insert(key, accepted, Actionable.MODULATE, IActionSource.empty());
                if (real <= 0) {
                    continue;
                }
                int gone = (int) Math.min(real, stack.m_41613_());
                bag.m_6836_(i, gone >= stack.m_41613_() ? ItemStack.f_41583_
                        : new ItemStack(item, stack.m_41613_() - gone));
                moved += gone;
                moving -= gone;
            }
            if (moved <= 0) {
                throw new ApiError(ErrorKind.FAILED, "ME 网络没收下 " + id,
                        "存储元件可能满了；用 ae2.net.here 看容量。");
            }
            return new Move(id, n, moved, via.via(),
                    "把 " + moved + " 个 " + id + " 存进了 ME 网络（" + via.via() + "）"
                            + (moved < n ? "，网络只收下这么多" : ""));
        });
    }
}
