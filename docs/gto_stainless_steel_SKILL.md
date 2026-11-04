---
name: gto_stainless_steel
description: 烧不锈钢的完整实操流程(GTOCore 魔改路线,亲测数据):混合机配粉、电力高炉(EBF)熔炼、电路配置坑、设备精确坐标、投料站位技巧、状态解读与排查顺序。含已验证步骤与最后一个未验证的收尾(通用电路配置位)。
---

# Skill: 烧不锈钢(gto_stainless_steel)

GTOCore 魔改路线烧不锈钢的完整实操手册。所有坐标、配方、坑都来自实机操作记录。

## 配方链(亲查,共 142+ 条相关配方中的关键三条)

1. **混合机**(mixer,单方块,MV 级):
   `铁粉 x6 + 镍粉 x1 + 锰粉 x1 + 铬粉 x1 + 编程电路(不消耗)→ 不锈钢粉 x9`
   - 备选:`铁粉 x4 + 因瓦粉 x3 + 锰粉 + 铬粉 + 电路 → 不锈钢粉 x9`
   - 因瓦粉:`铁粉 x2 + 镍粉 x1 + 电路 → 因瓦粉 x3`(mixer)
2. **电力高炉**(EBF 多方块):
   - `不锈钢粉 x8 + 编程电路 → 熔融不锈钢(流体)`(GTO 魔改配方 `molten_stainless_steel`,标准 GTM 没有)
   - `不锈钢粉 x1 + 编程电路 → 不锈钢锭 x1`(直烧版 `blast_stainless_steel`)
3. 熔融不锈钢 → 固化/铸造 → 锭(设备待实地确认)

注意:跨越式高炉(Leap Forward One Blast Furnace)是**土高炉**(primitive_blast_furnace 类型),只烧铁系(古法炼钢),**不收不锈钢**——别把粉塞错炉子。

## 设备坐标(玩家基地)

- 混合机:`gtceu:mv_mixer @(-98,65,-119)`(加工区,**未接电**——投料前先解决供电)
- EBF 控制器:`gtceu:electric_blast_furnace @(-94,65,-139)`
- EBF HV 物品输入仓:`gtceu:hv_input_hatch @(-92,66,-136)`(朝东)
- EBF HV 能源输入仓:`gtceu:hv_energy_input_hatch @(-95,65,-139)`
- EBF 无线能源输入仓:`gtocore:hv_wireless_input_hatch @(-95,65,-140)`(可接玩家无线电网)
- EBF 线圈:镍铬合金(nichrome)x9
- 旁边:扩展 AE2 接口 `@(-91,67,-136)`、维护仓 `@(-92,66,-135)`(会挡准星,见下)

## 电路坑(亲测教训)

- GTM 编程电路(`gtceu:programmed_circuit`)带配置 NBT(0-24),配方按配置匹配;配方输入的电路是「不消耗」催化剂(概率 0% 是它的标记)。
- GTO 通用电路(`gtocore:universal_circuit_<电压>`)按电压分级:LV 通用对应 LV 等级。
- **未解**:LV 通用电路投进 EBF 后仍「No Recipe found」——头号嫌疑是配置位不匹配(配方要特定配置编号,通用电路的配置 NBT 待验证)。
- 排查顺序:料是否匹配 → 电路配置 → 是否缺电(**缺电是 WAITING 状态;IDLE+No Recipe found 说明是配方没匹配上,别往电上找**)→ 机器是否被暂停。
- EBF 的 EU 需求从 HV 能源仓进;玩家光伏电网在电池缓冲那条线上,EBF 的无线能源仓可能需要用无线绑定工具对变电站绑定一次。

## 投料站位技巧(EBF 输入仓,东面被管道堵死)

- 输入仓朝东,正东贴脸格 `(-91,66,-136)` 被铝大管道占用。
- 可站空气格:`(-90,66,-136)`(正东 2 格)、`(-91,66,-135)`(东南斜邻)。
- **但从这些点准星常被维护仓 `(-92,66,-135)` 挡住**;numen.move.to 的 near 落点常偏到 `(-89,65,-133)`。
- 用 `numen.scan.sight` 验证可见性;实在不行换北/南面找开口。

## 操作流程

1. `ae2.ae2.take` 取料:不锈钢粉 x8、通用电路 x1(或按混合路线取四种粉)。
2. `numen.move.to` 走到输入仓(用上面的站位点,注意维护仓挡准星)。
3. `numen.use.block` 开仓 → `numen.gui.put` 投料(粉、电路)。
4. `gt.gt.status` 监控:active / progress / logic_status / idle_reason / recipe_types。
5. 产出:EBF 输出总线,或 AE2 接口自动回收进网络。

## 状态解读

- `IDLE + No Recipe found`:料/电路组合没匹配上配方(先查电路配置位)。
- `WAITING`:配方匹配但资源不足(缺电/缺料/缺流体),idle_reason 会写明。
- `WORKING`:在跑,读 progress/max_progress。
- 多方块「未成形」且刚重启过:可能只是结构检查还没跑,等一 tick 或触发一次方块更新。
