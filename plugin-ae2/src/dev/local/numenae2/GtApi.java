package dev.local.numenae2;

import java.util.ArrayList;
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
        boolean formed = true;
        boolean active = false;
        int progress = -1;
        int maxProgress = -1;
        String kind = "machine";
        if (be instanceof IMultiController mc) {
            kind = "multiblock";
            try {
                formed = mc.self().isFormed();
            } catch (Throwable ignored) {
                formed = false;
            }
        }
        if (be instanceof IRecipeLogicMachine rm) {
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
                    if (!(be instanceof IMultiController mc)) {
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
        if (!(be instanceof IMultiController) && !(be instanceof IRecipeLogicMachine)) {
            throw new ApiError(ErrorKind.NOT_FOUND, coord(pos) + " 处不是格雷机器：" + idOf(be),
                    "pos 要点多方块控制器或者单方块机器本体。");
        }
        return describe(pos, be);
    }
}
