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
                          int progress, int maxProgress, long eu, long maxEu, String line,
                          String recipeTypes, String logicStatus, String idleReason) {
    }

    private static Status describe(BlockPos pos, BlockEntity be) {
        String id = idOf(be);
        Object machine = machineOf(be);
        boolean formed = true;
        boolean active = false;
        int progress = -1;
        int maxProgress = -1;
        String kind = "machine";
        String logicStatus = "";
        String idleReason = "";
        String recipeTypes = "";
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
                var logic = rm.getRecipeLogic();
                if (logic != null) {
                    IWorkable w = logic;
                    active = w.isActive();
                    progress = w.getProgress();
                    maxProgress = w.getMaxProgress();
                    try {
                        int st = logic.getStatus();
                        logicStatus = switch (st) {
                            case 0 -> "IDLE";
                            case 1 -> "WORKING";
                            case 2 -> "WAITING";
                            case 3 -> "SUSPEND";
                            default -> String.valueOf(st);
                        };
                    } catch (Throwable ignored) {
                    }
                    try {
                        var ir = logic.getIdleReason();
                        if (ir != null) {
                            idleReason = ir.getString();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                var def = ((com.gregtechceu.gtceu.api.machine.MetaMachine) machine).definition;
                if (def != null && def.getRecipeTypes() != null) {
                    StringBuilder tb = new StringBuilder();
                    for (var t : def.getRecipeTypes()) {
                        if (tb.length() > 0) {
                            tb.append(", ");
                        }
                        tb.append(t.registryName.toString());
                    }
                    recipeTypes = tb.toString();
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
        if (!logicStatus.isEmpty()) {
            sb.append(" 状态:").append(logicStatus);
        }
        if (!idleReason.isEmpty()) {
            sb.append(" 待机原因:").append(idleReason);
        }
        if (eu[1] > 0) {
            sb.append(" EU ").append(eu[0]).append("/").append(eu[1]);
        }
        return new Status(coord(pos), id, kind, formed, active, progress, maxProgress, eu[0], eu[1], sb.toString(),
                recipeTypes, logicStatus, idleReason);
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

    // ------------------------------------------------------------------ uses (search by input)

    public record UsesOpt(Optional<String> input, Optional<Integer> limit, Optional<String> type_filter) {
    }

    public record Use(String recipeId, String category, String output, int count, String inputs) {
    }

    public record Uses(List<Use> rows, int matched, String line) {
    }

    private static String itemLabel(ItemStack stack) {
        ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return rl == null ? "?" : rl.toString();
    }

    @Fn("查全服务器所有把某物品当输入的配方(GT 与原版一起),按产物回列。")
    @Example("gt.gt.uses({input = \"gtceu:stainless_steel_dust\", limit = 30})")
    @Note("input 是物品 id。type_filter 可按配方类型名过滤。matched 是命中总数,rows 只带前 limit 条。")
    public static Uses uses(ServerCall call, UsesOpt args) {
        String input = args == null || args.input().isEmpty() ? "" : args.input().orElse("").trim();
        if (input.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说查谁的用途",
                    "写成 gt.gt.uses({input = \"gtceu:stainless_steel_dust\"})。");
        }
        int limit = args == null || args.limit().isEmpty() ? 30 : Math.max(1, Math.min(100, args.limit().get()));
        String typeFilter = args == null || args.type_filter().isEmpty() ? "" : args.type_filter().orElse("").trim();
        ServerLevel level = level(call);
        List<Use> rows = new ArrayList<>();
        int matched = 0;

        // ---- layer 1: GregTech recipe types
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
                        boolean takes = false;
                        StringBuilder ins = new StringBuilder();
                        for (Object co : def.itemInputs) {
                            com.gregtechceu.gtceu.api.recipe.content.Content<?> c =
                                    (com.gregtechceu.gtceu.api.recipe.content.Content<?>) co;
                            if (!(c.inner instanceof com.gregtechceu.gtceu.api.recipe.ingredient.ItemIngredient ii)) {
                                continue;
                            }
                            ItemStack[] opts = ii.inner.m_43908_();
                            if (opts == null) {
                                continue;
                            }
                            for (ItemStack o : opts) {
                                if (o.m_41619_()) {
                                    continue;
                                }
                                if (ins.length() > 0) {
                                    ins.append(" + ");
                                }
                                String oid = itemLabel(o);
                                if (oid.equals(input)) {
                                    takes = true;
                                }
                                ins.append(oid).append(" x").append(Math.max(1, c.amount));
                                break;
                            }
                        }
                        if (!takes) {
                            continue;
                        }
                        String rowLine = type.registryName.toString();
                        if (!typeFilter.isEmpty() && !rowLine.contains(typeFilter)) {
                            continue;
                        }
                        matched++;
                        if (rows.size() >= limit) {
                            continue;
                        }
                        appendGtOutputs(ins, def.itemOutputs);
                        String outId = "";
                        int outCount = 0;
                        for (Object co : def.itemOutputs) {
                            com.gregtechceu.gtceu.api.recipe.content.Content<?> c =
                                    (com.gregtechceu.gtceu.api.recipe.content.Content<?>) co;
                            if (c.inner instanceof com.gregtechceu.gtceu.api.recipe.ingredient.ItemIngredient ii) {
                                ItemStack[] opts = ii.inner.m_43908_();
                                if (opts != null && opts.length > 0) {
                                    outId = itemLabel(opts[0]);
                                    outCount = (int) Math.max(1, c.amount);
                                    break;
                                }
                            }
                        }
                        rows.add(new Use(entry.getKey().toString(), rowLine, outId, outCount, ins.toString()));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // ---- layer 2: vanilla RecipeManager
        var registries = level.m_9598_();
        for (net.minecraft.world.item.crafting.Recipe<?> r : level.m_7654_().m_129894_().m_44051_()) {
            try {
                boolean takes = false;
                StringBuilder ins = new StringBuilder();
                for (var ing : r.m_7527_()) {
                    if (ing == null) {
                        continue;
                    }
                    ItemStack[] opts = ing.m_43908_();
                    if (opts == null) {
                        continue;
                    }
                    for (ItemStack o : opts) {
                        if (o.m_41619_()) {
                            continue;
                        }
                        if (ins.length() > 0) {
                            ins.append(" | ");
                        }
                        String oid = itemLabel(o);
                        if (oid.equals(input)) {
                            takes = true;
                        }
                        ins.append(oid);
                        break;
                    }
                }
                if (!takes) {
                    continue;
                }
                ItemStack result = r.m_8043_(registries);
                if (result == null || result.m_41619_()) {
                    continue;
                }
                String type = typeName(r);
                if (!typeFilter.isEmpty() && !type.contains(typeFilter)) {
                    continue;
                }
                matched++;
                if (rows.size() >= limit) {
                    continue;
                }
                rows.add(new Use(r.m_6423_().toString(), type, itemLabel(result), result.m_41613_(), ins.toString()));
            } catch (Throwable ignored) {
            }
        }

        String line;
        if (matched == 0) {
            line = "全服务器没有把 " + input + " 当输入的配方";
        } else {
            line = "把 " + input + " 当输入的配方共 " + matched + " 条"
                    + (rows.size() < matched ? "(只列前 " + rows.size() + " 条)" : "");
        }
        return new Uses(rows, matched, line);
    }

    private static void appendGtOutputs(StringBuilder ins, List<?> outputs) {
        try {
            for (Object co : outputs) {
                com.gregtechceu.gtceu.api.recipe.content.Content<?> c =
                        (com.gregtechceu.gtceu.api.recipe.content.Content<?>) co;
                if (!(c.inner instanceof com.gregtechceu.gtceu.api.recipe.ingredient.ItemIngredient ii)) {
                    continue;
                }
                ItemStack[] opts = ii.inner.m_43908_();
                if (opts == null || opts.length == 0) {
                    continue;
                }
                ins.append(" => ");
                ResourceLocation rl = ForgeRegistries.ITEMS.getKey(opts[0].m_41720_());
                ins.append(rl == null ? "?" : rl.toString());
                if (c.amount > 1) {
                    ins.append(" x").append(c.amount);
                }
                if (c.chance != 10000) {
                    ins.append("(概率").append(c.chance / 100).append("%)");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ graph (recipe relations)

    /** One recipe, reduced to item-id adjacency for graph walks. */
    public static final class REntry {
        public final String id;
        public final String category;
        public final java.util.LinkedHashMap<String, Long> inputs = new java.util.LinkedHashMap<>();
        public final java.util.LinkedHashMap<String, Long> outputs = new java.util.LinkedHashMap<>();

        REntry(String id, String category) {
            this.id = id;
            this.category = category;
        }
    }

    private static volatile java.util.List<REntry> GRAPH;

    /** Build (once per server run) the full recipe graph: GT types + everything in the RecipeManager. */
    private static java.util.List<REntry> graph(ServerLevel level) {
        java.util.List<REntry> g = GRAPH;
        if (g != null) {
            return g;
        }
        synchronized (GtApi.class) {
            if (GRAPH != null) {
                return GRAPH;
            }
            java.util.List<REntry> all = new java.util.ArrayList<>();
            // GT layer
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
                            REntry re = new REntry(entry.getKey().toString(), type.registryName.toString());
                            fillGt(re, entry.getValue());
                            if (!re.inputs.isEmpty() || !re.outputs.isEmpty()) {
                                all.add(re);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // vanilla layer
            try {
                var registries = level.m_9598_();
                for (net.minecraft.world.item.crafting.Recipe<?> r : level.m_7654_().m_129894_().m_44051_()) {
                    try {
                        REntry re = new REntry(r.m_6423_().toString(), typeName(r));
                        ItemStack result = r.m_8043_(registries);
                        if (result != null && !result.m_41619_()) {
                            re.outputs.put(itemLabel(result), (long) result.m_41613_());
                        }
                        for (var ing : r.m_7527_()) {
                            if (ing == null) {
                                continue;
                            }
                            ItemStack[] opts = ing.m_43908_();
                            if (opts != null && opts.length > 0 && !opts[0].m_41619_()) {
                                re.inputs.put(itemLabel(opts[0]), 1L);
                            }
                        }
                        if (!re.inputs.isEmpty() || !re.outputs.isEmpty()) {
                            all.add(re);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            GRAPH = all;
            return all;
        }
    }

    @SuppressWarnings("unchecked")
    private static void fillGt(REntry re, com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition def) {
        for (Object co : def.itemInputs) {
            com.gregtechceu.gtceu.api.recipe.content.Content<?> c =
                    (com.gregtechceu.gtceu.api.recipe.content.Content<?>) co;
            if (c.inner instanceof com.gregtechceu.gtceu.api.recipe.ingredient.ItemIngredient ii) {
                ItemStack[] opts = ii.inner.m_43908_();
                if (opts != null && opts.length > 0 && !opts[0].m_41619_()) {
                    re.inputs.put(itemLabel(opts[0]), Math.max(1, c.amount));
                }
            }
        }
        for (Object co : def.itemOutputs) {
            com.gregtechceu.gtceu.api.recipe.content.Content<?> c =
                    (com.gregtechceu.gtceu.api.recipe.content.Content<?>) co;
            if (c.inner instanceof com.gregtechceu.gtceu.api.recipe.ingredient.ItemIngredient ii) {
                ItemStack[] opts = ii.inner.m_43908_();
                if (opts != null && opts.length > 0 && !opts[0].m_41619_()) {
                    re.outputs.put(itemLabel(opts[0]), Math.max(1, c.amount));
                }
            }
        }
    }

    private static java.util.Map<String, java.util.List<REntry>> index(java.util.List<REntry> all, boolean byOutput) {
        java.util.Map<String, java.util.List<REntry>> ix = new java.util.HashMap<>();
        for (REntry re : all) {
            var keys = byOutput ? re.outputs.keySet() : re.inputs.keySet();
            for (String k : keys) {
                ix.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add(re);
            }
        }
        return ix;
    }

    public record PathOpt(Optional<String> from, Optional<String> to, Optional<Integer> maxDepth) {
    }

    public record Step(String recipeId, String category, String inputs, String outputs) {
    }

    public record Paths(boolean found, int depth, String direction, List<Step> steps, String line) {
    }

    @Fn("在配方图上找两个物品之间的生产路径:A 能否一步步走到 B(或反向:B 的原料链里有没有 A)。")
    @Example("gt.gt.path({from = \"gtceu:iron_dust\", to = \"gtceu:stainless_steel_dust\", maxDepth = 4})")
    @Note("默认从 to 反向找原料链(GT 的中间产物越往上越多,反推更快)。循环产线会被 visited 截断,不会绕死。")
    public static Paths path(ServerCall call, PathOpt args) {
        String from = args == null || args.from().isEmpty() ? "" : args.from().orElse("").trim();
        String to = args == null || args.to().isEmpty() ? "" : args.to().orElse("").trim();
        if (from.isEmpty() || to.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "起点终点都要给",
                    "写成 gt.gt.path({from = \"gtceu:iron_dust\", to = \"gtceu:stainless_steel_dust\"})。");
        }
        int maxDepth = args == null || args.maxDepth().isEmpty() ? 4 : Math.max(1, Math.min(8, args.maxDepth().get()));
        java.util.List<REntry> all = graph(level(call));
        var usesIx = index(all, false);
        var makesIx = index(all, true);

        // BFS from `to` backwards along makes-index: which raw inputs can reach `to`.
        java.util.Map<String, String> prevNode = new java.util.HashMap<>();
        java.util.Map<String, REntry> prevRecipe = new java.util.HashMap<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(to);
        visited.add(to);
        boolean found = to.equals(from);
        int depth = 0;
        while (!queue.isEmpty() && !found && depth < maxDepth) {
            int size = queue.size();
            for (int s = 0; s < size && !found; s++) {
                String cur = queue.poll();
                for (REntry re : makesIx.getOrDefault(cur, java.util.List.of())) {
                    for (String nxt : re.inputs.keySet()) {
                        if (visited.contains(nxt)) {
                            continue;
                        }
                        visited.add(nxt);
                        prevNode.put(nxt, cur);
                        prevRecipe.put(nxt, re);
                        if (nxt.equals(from)) {
                            found = true;
                            break;
                        }
                        queue.add(nxt);
                    }
                    if (found) {
                        break;
                    }
                }
            }
            depth++;
        }

        List<Step> steps = new ArrayList<>();
        String line;
        if (!found) {
            line = "在深度 " + maxDepth + " 内没有找到 " + from + " → " + to + " 的生产路径";
            return new Paths(false, -1, "backward", steps, line);
        }
        // walk back from `from` via prev maps until `to`
        String cur = from;
        int guard = 0;
        while (!cur.equals(to) && guard++ < 64) {
            REntry re = prevRecipe.get(cur);
            if (re == null) {
                break;
            }
            StringBuilder ins = new StringBuilder();
            for (var e : re.inputs.entrySet()) {
                if (ins.length() > 0) {
                    ins.append(" + ");
                }
                ins.append(e.getKey());
                if (e.getValue() > 1) {
                    ins.append(" x").append(e.getValue());
                }
            }
            StringBuilder outs = new StringBuilder();
            for (var e : re.outputs.entrySet()) {
                if (outs.length() > 0) {
                    outs.append(" | ");
                }
                outs.append(e.getKey());
                if (e.getValue() > 1) {
                    outs.append(" x").append(e.getValue());
                }
            }
            steps.add(new Step(re.id, re.category, ins.toString(), outs.toString()));
            String prev = prevNode.get(cur);
            if (prev == null) {
                break;
            }
            cur = prev;
        }
        line = "找到路径(" + steps.size() + " 步):" + from + " → " + to;
        return new Paths(true, steps.size(), "backward", steps, line);
    }

    public record LoopOpt(Optional<String> item, Optional<Integer> maxDepth) {
    }

    @Fn("检测某物品是否处在循环产线里:从它出发沿消耗边走,能回到它自己即为环。")
    @Example("gt.gt.loop({item = \"gtceu:steel_ingot\", maxDepth = 5})")
    @Note("GT 里循环产线很多(比如钢渣回炉),这条会给出一个具体的环例子。")
    public static Paths loop(ServerCall call, LoopOpt args) {
        String item = args == null || args.item().isEmpty() ? "" : args.item().orElse("").trim();
        if (item.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说查哪个物品",
                    "写成 gt.gt.loop({item = \"gtceu:steel_ingot\"})。");
        }
        int maxDepth = args == null || args.maxDepth().isEmpty() ? 5 : Math.max(1, Math.min(8, args.maxDepth().get()));
        java.util.List<REntry> all = graph(level(call));
        var usesIx = index(all, false);

        // DFS with path tracking; a back-edge to a node on the current stack is a loop.
        java.util.Map<String, String> prevNode = new java.util.HashMap<>();
        java.util.Map<String, REntry> prevRecipe = new java.util.HashMap<>();
        java.util.List<String> stack = new java.util.ArrayList<>();
        java.util.Set<String> onStack = new java.util.HashSet<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        List<Step> steps = new ArrayList<>();
        String hit = null;

        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(item);
        onStack.add(item);
        stack.add(item);
        while (!queue.isEmpty() && hit == null) {
            String cur = queue.poll();
            for (REntry re : usesIx.getOrDefault(cur, java.util.List.of())) {
                for (String nxt : re.outputs.keySet()) {
                    if (nxt.equals(item)) {
                        prevNode.put(nxt, cur);
                        prevRecipe.put(nxt, re);
                        hit = cur;
                        break;
                    }
                    if (done.contains(nxt) || onStack.contains(nxt)) {
                        continue;
                    }
                    done.add(nxt);
                    onStack.add(nxt);
                    stack.add(nxt);
                    prevNode.put(nxt, cur);
                    prevRecipe.put(nxt, re);
                    queue.add(nxt);
                }
                if (hit != null) {
                    break;
                }
            }
            if (hit == null) {
                // pop
                String popped = stack.remove(stack.size() - 1);
                onStack.remove(popped);
                if (stack.size() > maxDepth) {
                    break;
                }
            }
        }

        List<Step> reversed = new ArrayList<>();
        if (hit != null) {
            String cur = item;
            int guard = 0;
            while (guard++ < 64) {
                REntry re = prevRecipe.get(cur);
                if (re == null) {
                    break;
                }
                StringBuilder ins = new StringBuilder();
                for (var e : re.inputs.entrySet()) {
                    if (ins.length() > 0) {
                        ins.append(" + ");
                    }
                    ins.append(e.getKey());
                    if (e.getValue() > 1) {
                        ins.append(" x").append(e.getValue());
                    }
                }
                StringBuilder outs = new StringBuilder();
                for (var e : re.outputs.entrySet()) {
                    if (outs.length() > 0) {
                        outs.append(" | ");
                    }
                    outs.append(e.getKey());
                    if (e.getValue() > 1) {
                        outs.append(" x").append(e.getValue());
                    }
                }
                reversed.add(new Step(re.id, re.category, ins.toString(), outs.toString()));
                String prev = prevNode.get(cur);
                if (prev == null || prev.equals(item)) {
                    break;
                }
                cur = prev;
            }
            java.util.Collections.reverse(reversed);
            steps.addAll(reversed);
        }
        String line = hit == null
                ? "深度 " + maxDepth + " 内没有发现经过 " + item + " 的循环产线"
                : "发现循环产线(共 " + steps.size() + " 步回到 " + item + ")";
        return new Paths(hit != null, steps.size(), "loop", steps, line);
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
