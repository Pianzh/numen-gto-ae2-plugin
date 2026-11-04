package dev.local.numenae2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.ServerCall;

import com.gregtechceu.gtceu.api.capability.IWorkable;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code gt}: read GregTech machines and multiblocks without scanning part blocks.
 *
 * <p>{@code numen.scan.blocks} crashes on GT part-type blocks (input/output bus etc.), and a
 * multiblock's recipe state lives in block entities anyway - so this group walks the loaded
 * chunks' block entities directly: controllers are {@link IMultiController}, runnable machines
 * are {@link IRecipeLogicMachine}. Formed/active/progress/EU come from the machine itself,
 * never from block names.
 */
public final class GtApi {

    private GtApi() {
    }

    static void install(NumenApi numen) {
        numen.api("gt", "格雷科技：列出周围的多方块控制器与机器，读成形状态、运行进度、EU 存量。", GtApi.class);
    }

    // ------------------------------------------------------------------ helpers

    private static ServerLevel level(ServerCall call) {
        return (ServerLevel) call.her().m_9236_();
    }

    private static BlockPos pos3(String s) {
        String[] p = s.trim().split(",");
        if (p.length != 3) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "坐标读不懂：" + s,
                    "pos 写成三个整数，例如 \"-72,66,-96\"。");
        }
        try {
            return new BlockPos(Integer.parseInt(p[0].trim()),
                    Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
        } catch (NumberFormatException e) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "坐标读不懂：" + s,
                    "pos 写成三个整数，例如 \"-72,66,-96\"。");
        }
    }

    private static String idOf(BlockEntity be) {
        try {
            ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(be.m_58900_().m_60734_());
            return rl == null ? "?" : rl.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** The GT machine object a block entity holds (GTM keeps features on MetaMachine, not the BE). */
    private static Object machineOf(BlockEntity be) {
        try {
            if (be instanceof com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity mb) {
                return mb.getMetaMachine();
            }
        } catch (Throwable ignored) {
        }
        return be;
    }

    private static long[] euOf(BlockEntity be) {
        try {
            Object cap = ((net.minecraftforge.common.capabilities.ICapabilityProvider) (Object) be)
                    .getCapability(ForgeCapabilities.ENERGY).orElse(null);
            if (cap instanceof IEnergyStorage e) {
                return new long[] { e.getEnergyStored(), e.getMaxEnergyStored() };
            }
        } catch (Throwable ignored) {
        }
        return new long[] { -1, -1 };
    }

    private record Status(String pos, String id, String kind, boolean formed, boolean active,
                          int progress, int maxProgress, long eu, long maxEu, String line) {
    }

    private static Status describe(BlockPos pos, BlockEntity be) {
        String id = idOf(be);
        Object machine = machineOf(be);
        boolean formed = true;
        boolean active = false;
        int progress = -1;
        int maxProgress = -1;
        String kind = "machine";
        if (machine instanceof IMultiController mc) {
            kind = "multiblock";
            try {
                formed = mc.self().isFormed();
            } catch (Throwable ignored) {
                formed = false;
            }
        }
        if (machine instanceof IRecipeLogicMachine rm) {
            try {
                IWorkable w = rm.getRecipeLogic();
                if (w != null) {
                    active = w.isActive();
                    progress = w.getProgress();
                    maxProgress = w.getMaxProgress();
                }
            } catch (Throwable ignored) {
            }
        }
        long[] eu = euOf(be);
        StringBuilder sb = new StringBuilder();
        sb.append(id).append(" @").append(pos.m_123341_()).append(",").append(pos.m_123342_())
                .append(",").append(pos.m_123343_());
        if (kind.equals("multiblock")) {
            sb.append(formed ? "［已成形］" : "［未成形］");
        }
        if (progress >= 0) {
            sb.append(active ? " 运行中 " : " 待机 ");
            sb.append(progress).append("/").append(maxProgress).append(" tick");
        }
        if (eu[1] > 0) {
            sb.append(" EU ").append(eu[0]).append("/").append(eu[1]);
        }
        return new Status(coord(pos), id, kind, formed, active, progress, maxProgress, eu[0], eu[1], sb.toString());
    }

    private static String coord(BlockPos p) {
        return p.m_123341_() + "," + p.m_123342_() + "," + p.m_123343_();
    }

    // ------------------------------------------------------------------ listing

    public record FindOpt(Optional<Integer> radius, Optional<Boolean> unformedOnly) {
    }

    public record Multi(String pos, String id, boolean formed, String line) {
    }

    public record Multis(List<Multi> found, int scanned, String line) {
    }

    @Fn("列出她周围（默认 ±2 区块）所有格雷多方块控制器：位置、类型、是否成形。")
    @Example("gt.gt.find()")
    @Example("gt.gt.find({radius = 3, unformedOnly = true})")
    @Note("unformedOnly 为 true 时只列未成形的——找烂尾工程最方便。遍历的是方块实体，"
            + "不会踩 numen.scan.blocks 对格雷部件方块的崩溃。")
    public static Multis find(ServerCall call, FindOpt args) {
        int radius = args == null || args.radius().isEmpty() ? 2 : Math.max(1, Math.min(4, args.radius().get()));
        boolean unformedOnly = args != null && args.unformedOnly().isPresent() && args.unformedOnly().get();
        ServerLevel level = level(call);
        BlockPos center = call.her().m_20183_();
        int ccx = center.m_123341_() >> 4;
        int ccz = center.m_123343_() >> 4;
        List<Multi> out = new ArrayList<>();
        int scanned = 0;
        for (int cx = ccx - radius; cx <= ccx + radius; cx++) {
            for (int cz = ccz - radius; cz <= ccz + radius; cz++) {
                if (!level.m_7232_(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                for (var entry : chunk.m_62954_().entrySet()) {
                    BlockEntity be = entry.getValue();
                    if (!(machineOf(be) instanceof IMultiController mc)) {
                        continue;
                    }
                    scanned++;
                    boolean formed;
                    try {
                        formed = mc.self().isFormed();
                    } catch (Throwable ignored) {
                        formed = false;
                    }
                    if (unformedOnly && formed) {
                        continue;
                    }
                    BlockPos p = entry.getKey();
                    out.add(new Multi(coord(p), idOf(be), formed,
                            idOf(be) + " @" + coord(p) + (formed ? "［已成形］" : "［未成形］")));
                }
            }
        }
        out.sort((a, b) -> a.pos().compareTo(b.pos()));
        String line;
        if (out.isEmpty()) {
            line = unformedOnly ? "她周围没有未成形的格雷多方块" : "她周围 ±" + radius + " 区块没有格雷多方块控制器";
        } else {
            StringBuilder sb = new StringBuilder("找到 ").append(out.size()).append(" 台多方块控制器（扫过 ")
                    .append(scanned).append(" 个）：");
            for (Multi m : out) {
                sb.append("\n").append(m.line());
            }
            line = sb.toString();
        }
        return new Multis(out, scanned, line);
    }

    // ------------------------------------------------------------------ recipes (all mods)

    public record RecipeOpt(Optional<String> output, Optional<String> type_filter, Optional<Integer> limit) {
    }

    public record RecipeRow(String recipeId, String category, String output, int count, String inputs) {
    }

    public record Recipes(List<RecipeRow> rows, int matched, String line) {
    }

    private static String typeName(net.minecraft.world.item.crafting.Recipe<?> r) {
        try {
            net.minecraft.world.item.crafting.RecipeType<?> t = r.m_6671_();
            if (t instanceof com.gregtechceu.gtceu.api.recipe.GTRecipeType gt) {
                return gt.registryName.toString();
            }
            return String.valueOf(t);
        } catch (Throwable t2) {
            return "unknown";
        }
    }

    @Fn("查全服务器所有 mod 的配方:按产物物品 id 过滤,原版与格雷机器配方一起出。")
    @Example("gt.gt.recipe({output = \"gtceu:stainless_steel_ingot\"})")
    @Example("gt.gt.recipe({output = \"gtceu:steel_dust\", limit = 20})")
    @Note("category 是配方类型的注册名(格雷机器配方都是 gtceu:xxx 这类)。inputs 是输入物品的简列。")
    public static Recipes recipe(ServerCall call, RecipeOpt args) {
        String output = args == null || args.output().isEmpty() ? "" : args.output().orElse("").trim();
        if (output.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说查什么产物",
                    "写成 gt.gt.recipe({output = \"gtceu:stainless_steel_ingot\"})。");
        }
        int limit = args == null || args.limit().isEmpty() ? 12 : Math.max(1, Math.min(40, args.limit().get()));
        String typeFilter = args == null || args.type_filter().isEmpty() ? "" : args.type_filter().orElse("").trim();
        ServerLevel level = level(call);
        List<RecipeRow> rows = new ArrayList<>();

        // ---- layer 1: GregTech recipe types (they do not live in the vanilla RecipeManager)
        try {
            Class<?> reg = Class.forName("com.gregtechceu.gtceu.common.data.GTRecipeTypes");
            for (java.lang.reflect.Field f : reg.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !com.gregtechceu.gtceu.api.recipe.GTRecipeType.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                com.gregtechceu.gtceu.api.recipe.GTRecipeType type =
                        (com.gregtechceu.gtceu.api.recipe.GTRecipeType) f.get(null);
                if (type == null || type.recipes == null) {
                    continue;
                }
                for (var entry : type.recipes.entrySet()) {
                    try {
                        com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition def = entry.getValue();
                        if (def == null) {
                            continue;
                        }
                        String outId = null;
                        int count = 0;
                        StringBuilder ins = new StringBuilder();
                        boolean hit = false;
                        for (Object co : def.itemOutputs) {
                            com.gregtechceu.gtceu.api.recipe.content.Content<?> c =
                                    (com.gregtechceu.gtceu.api.recipe.content.Content<?>) co;
                            if (!(c.inner instanceof com.gregtechceu.gtceu.api.recipe.ingredient.ItemIngredient ii)) {
                                continue;
                            }
                            ItemStack[] opts = ii.inner.m_43908_();
                            if (opts == null || opts.length == 0) {
                                continue;
                            }
                            ResourceLocation rl = ForgeRegistries.ITEMS.getKey(opts[0].m_41720_());
                            String rid = rl == null ? "?" : rl.toString();
                            if (rid.equals(output)) {
                                hit = true;
                                outId = rid;
                                count = (int) Math.max(1, c.amount);
                            }
                        }
                        if (!hit) {
                            continue;
                        }
                        appendGtInputs(ins, def.itemInputs);
                        appendGtFluids(ins, def.fluidInputs, true);
                        String rowLine = type.registryName.toString();
                        if (!typeFilter.isEmpty() && !rowLine.contains(typeFilter)) {
                            continue;
                        }
                        rows.add(new RecipeRow(entry.getKey().toString(), rowLine,
                                outId == null ? output : outId, count, ins.toString()));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // ---- layer 2: everything registered the vanilla way
        var registries = level.m_9598_();
        for (net.minecraft.world.item.crafting.Recipe<?> r : level.m_7654_().m_129894_().m_44051_()) {
            try {
                ItemStack result = r.m_8043_(registries);
                if (result == null || result.m_41619_()) {
                    continue;
                }
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(result.m_41720_());
                String rid = rl == null ? "?" : rl.toString();
                if (!rid.equals(output)) {
                    continue;
                }
                String type = typeName(r);
                if (!typeFilter.isEmpty() && !type.contains(typeFilter)) {
                    continue;
                }
                StringBuilder ins = new StringBuilder();
                for (var ing : r.m_7527_()) {
                    if (ing == null) {
                        continue;
                    }
                    ItemStack[] opts = ing.m_43908_();
                    if (opts == null || opts.length == 0) {
                        continue;
                    }
                    if (ins.length() > 0) {
                        ins.append(" | ");
                    }
                    int listed = 0;
                    for (ItemStack o : opts) {
                        if (o.m_41619_() || listed >= 3) {
                            break;
                        }
                        ResourceLocation orl = ForgeRegistries.ITEMS.getKey(o.m_41720_());
                        ins.append(orl == null ? o.toString() : orl.toString());
                        listed++;
                    }
                    if (opts.length > 3) {
                        ins.append("(+").append(opts.length - 3).append(")");
                    }
                }
                rows.add(new RecipeRow(r.m_6423_().toString(), typeName(r), rid, result.m_41613_(), ins.toString()));
            } catch (Throwable ignored) {
            }
        }

        List<RecipeRow> shown = rows.size() > limit ? rows.subList(0, limit) : rows;
        String line;
        if (rows.isEmpty()) {
            line = "全服务器配方里没有产出 " + output + " 的条目";
        } else {
            StringBuilder sb = new StringBuilder("产出 ").append(output).append(" 的配方 ")
                    .append(rows.size()).append(" 条");
            if (shown.size() < rows.size()) {
                sb.append("(只列前 ").append(shown.size()).append(")");
            }
            for (RecipeRow row : shown) {
                sb.append("\n[").append(row.category()).append("] ").append(row.recipeId())
                        .append(" x").append(row.count()).append(" <= ").append(row.inputs());
            }
            line = sb.toString();
        }
        return new Recipes(shown, rows.size(), line);
    }

    private static void appendGtInputs(StringBuilder ins, List<?> inputs) {
        try {
            for (Object co : inputs) {
                com.gregtechceu.gtceu.api.recipe.content.Content<?> c =
                        (com.gregtechceu.gtceu.api.recipe.content.Content<?>) co;
                if (!(c.inner instanceof com.gregtechceu.gtceu.api.recipe.ingredient.ItemIngredient ii)) {
                    continue;
                }
                ItemStack[] opts = ii.inner.m_43908_();
                if (opts == null || opts.length == 0) {
                    continue;
                }
                if (ins.length() > 0) {
                    ins.append(" + ");
                }
                long amount = c.amount;
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(opts[0].m_41720_());
                ins.append(rl == null ? "?" : rl.toString());
                if (amount > 1) {
                    ins.append(" x").append(amount);
                }
                if (c.chance != 10000) {
                    ins.append("(概率").append(c.chance / 100).append("%)");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void appendGtFluids(StringBuilder ins, List<?> fluids, boolean isInput) {
        try {
            for (Object co : fluids) {
                com.gregtechceu.gtceu.api.recipe.content.Content<?> c =
                        (com.gregtechceu.gtceu.api.recipe.content.Content<?>) co;
                if (c.inner == null) {
                    continue;
                }
                String name = String.valueOf(c.inner);
                if (ins.length() > 0) {
                    ins.append(isInput ? " + " : " | ");
                }
                ins.append(name + " (fluid)");
                if (c.amount > 0) {
                    ins.append(" x").append(c.amount);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public record Types(List<String> types, String line) {
    }

    @Fn("列出服务器上全部配方类型(GT 的和原版的)以及每个类型有多少条配方。")
    @Example("gt.gt.types()")
    @Note("想看某台机器能烧什么,先在这里找它的类型名,再用 recipe 的 type_filter 过滤。")
    public static Types types(ServerCall call) {
        java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>();
        try {
            Class<?> reg = Class.forName("com.gregtechceu.gtceu.common.data.GTRecipeTypes");
            for (java.lang.reflect.Field f : reg.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !com.gregtechceu.gtceu.api.recipe.GTRecipeType.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                com.gregtechceu.gtceu.api.recipe.GTRecipeType type =
                        (com.gregtechceu.gtceu.api.recipe.GTRecipeType) f.get(null);
                if (type != null && type.recipes != null) {
                    counts.put(type.registryName.toString(), type.recipes.size());
                }
            }
        } catch (Throwable ignored) {
        }
        for (net.minecraft.world.item.crafting.Recipe<?> r : level(call).m_7654_().m_129894_().m_44051_()) {
            String t = typeName(r);
            counts.merge(t, 1, Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        int total = 0;
        for (var e : counts.entrySet()) {
            total += e.getValue();
            lines.add(e.getKey() + " x" + e.getValue());
        }
        return new Types(lines, "配方类型 " + counts.size() + " 种、配方共 " + total + " 条");
    }

    // ------------------------------------------------------------------ status

    public record PosOpt(Optional<String> pos) {
    }

    @Fn("读一台格雷机器（多方块控制器或单方块机器）的详细状态：成形、运行、进度、EU。")
    @Example("gt.gt.status({pos = \"-75,65,-122\"})")
    @Note("pos 点控制器的坐标。单方块机器没有成形概念，直接报运行状态。")
    public static Status status(ServerCall call, PosOpt args) {
        ServerLevel level = level(call);
        if (args == null || args.pos().isEmpty() || args.pos().get().trim().isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说看哪台",
                    "写成 gt.gt.status({pos = \"-72,66,-96\"})，pos 是控制器的坐标。");
        }
        BlockPos pos = pos3(args.pos().get());
        BlockEntity be = level.m_7702_(pos);
        if (be == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, coord(pos) + " 处没有方块实体",
                    "坐标写对了吗？多方块控制器在结构边缘上。");
        }
        if (!(machineOf(be) instanceof IMultiController) && !(machineOf(be) instanceof IRecipeLogicMachine)) {
            throw new ApiError(ErrorKind.NOT_FOUND, coord(pos) + " 处不是格雷机器：" + idOf(be),
                    "pos 要点多方块控制器或者单方块机器本体。");
        }
        return describe(pos, be);
    }
}
