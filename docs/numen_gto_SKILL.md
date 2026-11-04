---
name: numen_gto
description: 自研插件 numen_gto 的函数组使用手册。ae2.ae2.*(AE2 网络查询/取放)、gt.gt.*(格雷多方块与机器状态、全服务器配方查询、按输入查用途、两物品间生产路径、循环产线检测)。含调用格式、参数规则(snake_case、Lua 保留字禁用)、电压闸门、实测坑位与排查顺序。想查 GTOCore 任务书知识用 gto_quests skill。
---

# Skill: numen_gto 插件使用手册

本手册对应自研 mod `numen_gto`(源码:github.com/Pianzh/numen-gto-ae2-plugin)。它给 numen 注册了两组 Lua 函数:`ae2.ae2.*` 和 `gt.gt.*`。**注意双前缀:组名 + 命名空间,调用时都要写。**

## 通用规则(先读,全是实测坑)

1. **参数名一律 snake_case**:Java record 字段 `maxTier` 在 Lua 端是 `max_tier`;`unformedOnly` 是 `unformed_only`。
2. **参数名不能用 Lua 保留字**:`type` 会让整组函数注册失败(连累同 mod 的其他组),用 `type_filter` 这类名字。
3. **电压参数填电压名**: `"HV"`、 `"EV"`、 `"LuV"`……(ULV 到 UXV 全档),内部换算成 EU 上限过滤。
4. **配方图首次访问要构建**(4.5 万配方,1-2 秒),之后同一次服务端运行内毫秒级。
5. **格雷部件方块(输入总线/仓等)不要用 numen.scan.blocks 批量扫**——会触发上游崩溃;用 `gt.gt.find` / `gt.gt.status`(走方块实体)或单点 `numen.scan.block`。
6. **AE2 精确 id 匹配不含 NBT**:带配置的物品(如编程电路)用 `find` 模糊搜。
7. **状态深读**:`gt.gt.status` 会带出 recipe_types(机器支持的配方类型)、logic_status(IDLE/WAITING/WORKING/SUSPEND)、idle_reason(待机原因)。排查顺序:料匹配 → 电路配置 → 缺电(WAITING)→ 暂停。

## ae2.ae2.* — ME 网络读写

| 函数 | 参数 | 说明 |
|---|---|---|
| `drives()` | - | 列周围已加载区块的 AE2 设备坐标 |
| `here({pos})` | pos 可选 | 网络概览:种类数/总件数/节点数 |
| `count({id, pos})` | id 必填 | 某物品数量(流体带 (fluid) 标记) |
| `find({query, limit, pos})` | query 必填 | 按名字/id 片段搜索,按数量降序 |
| `take({id, count, pos})` | 均可 | 取物进背包(需主人授权弹窗) |
| `put({id, count, pos})` | 均可 | 从背包存入网络(需授权) |

- pos = `"x,y,z"` 字符串,给任意一台已成形供能的 AE2 设备;不给则自动找最近。
- 数据直读 `IStorageService#getInventory()`,不经 GUI,虚拟槽无关。

## gt.gt.* — 格雷机器与配方图

| 函数 | 参数 | 说明 |
|---|---|---|
| `find({radius, unformed_only})` | radius=1-4(区块), 默认 2 | 列周围多方块控制器:类型/位置/成形状态 |
| `status({pos})` | pos 必填 | 机器深读:成形/active/progress/逻辑状态/待机原因/支持的配方类型 |
| `recipe({output, type_filter, limit, max_tier})` | output 必填 | 按产物查配方(GT 层 + vanilla 层双源),行首带 [电压] 标签 |
| `uses({input, limit, type_filter, max_tier})` | input 必填 | 按输入查配方(谁消耗它)——追中间产物链的利器 |
| `path({from, to, max_depth, max_tier})` | from/to 必填 | 两物品间生产路径(反向 BFS 原料链),返回逐步配方 |
| `loop({item, max_depth})` | item 必填 | 循环产线检测,给出具体环(GT 循环产线很多) |
| `types()` | - | 列全部配方类型及各自配方数(112 种、4.5 万条) |

## 电压闸门(max_tier)

所有配方查询都支持 `max_tier = "HV"` 这类参数:超过该电压的配方直接过滤,行首带 `[HV]` 电压标签。**查当前阶段能用的配方时务必带上**,否则 UEV/UHV 的天顶科技会混进来。

## 实战样例(不锈钢链,亲测)

```lua
-- 铁粉 → 不锈钢粉 的路径(混合配方,1 步)
gt.gt.path({from = "gtceu:iron_dust", to = "gtceu:stainless_steel_dust", max_tier = "HV"})
-- 不锈钢粉的去向(4 条:离心回收、EBF 熔融、EBF 直烧)
gt.gt.uses({input = "gtceu:stainless_steel_dust", max_tier = "HV"})
-- 不锈钢锭的全部烧法(142 条,先看类型分布再 type_filter 收窄)
gt.gt.types()
gt.gt.recipe({output = "gtceu:stainless_steel_ingot", type_filter = "electric_blast_furnace", max_tier = "HV"})
```

## 已知边界

- 流体未进配方图:`molten_stainless_steel` 之类的流体链查不到(下一版把 fluidInputs/Outputs 建进图)。
- 部分任务/物品上游无 title 翻译键,显示为 UUID 是数据原样。
- 上游 issue 清单:scan.blocks 部件崩溃、Forge lang 缺失、jarjar 资源不挂载、Lua 保留字参数、RecipeType NPE——都已记录,等提给 dwinovo。
