# 嵌套 Embed 设计

**日期：**2026-08-26
**修订：**2026-09-18 —— 真实 SDK 全量构建基线驱动的「消费端类扁平聚合」改造，取代原「每层完整重做」的分层组合。修订依据见「性能基线与成本模型」，逐条变更见「变更记录」。

## 目标

让 fat-aar 通过嵌套 Embed 支持本地项目的递归嵌入。例如：

```groovy
// :lib-main
dependencies {
    embed project(':lib-aar')
}

// :lib-aar
dependencies {
    embed project(':lib-aar2')
}
```

最终 `lib-main.aar` 必须包含 `lib-main`、`lib-aar` 和 `lib-aar2` 的内容。该行为应支持任意嵌套深度，根项目不必重复声明每一个下层项目的 `embed` 依赖。

本次修订在原目标之上追加两条硬性目标：

1. **打包耗时从 `O(深度 × 子树内容)` 降到 `O(全图自有内容)`。** 同一份 class 在每个祖先层各被解包、合并、拷贝、改写、压缩一次，是当前基线里最大的单项开销。
2. **不为性能改变最终 AAR 的内容语义，也不要求使用者重组模块结构。** 优化必须落在插件内部；每个中间模块仍必须能独立 `assemble` / 发布出内容完整的 fat AAR。

## 性能基线与成本模型

基线取自真实 SDK 工程的干净全量构建（`--rerun-tasks` 语义的全量构建）：墙钟 30m41s，1279 个任务中 1274 个执行。

| 指标 | 干净全量（基线） |
| --- | --- |
| fat-aar 任务合计 | 920.4 s（15.3 min），占墙钟 50.0% |
| 嵌套链 7 层合计 | 733.1 s，占 fat-aar 79.6% |
| `mergeClasses` 合计 | 337.1 s（38.6%）：`copyToJavac` 162.6 + `unpackClassesJar` 143.5 + `writeIndex` 31.0 |
| `transformR` | 210.4 s（22.9%） |
| `reBundleAar` + `unpackBundleAar` | 244.0 s |
| `explode` | 107.7 s / 63 次 |
| `mergeClasses.cleanup` | 0.0 s（57.4 s 是增量构建专属开销，全量下无索引可删） |

嵌套链逐层明细：

| 层级 | 耗时 | 消费的输入 AAR |
| --- | --- | --- |
| 层 1（最底层） | 73.0 s | 53.4 MB / 135 条 |
| 层 2 | 90.4 s | 171.5 MB / 3313 条 |
| 层 3 | 90.8 s | 172.3 MB / 3315 条 |
| 层 4 | 73.2 s | 177.3 MB / 3410 条 |
| 层 5 | 85.6 s | 177.4 MB / 3413 条 |
| 层 6 | 160.4 s | 177.5 MB + 若干 |
| 层 7（消费根） | 159.7 s | 321.5 MB / 6071 条 |
| **合计** | **733.1 s** | |

三次独立测量指向同一结论：

- 中间 4 层的输入 AAR 体积几乎不变（171.5 → 177.4 MB），每层 `classes.jar` 都是约 25 MB：**同一批类被 7 次解包、合并、拷贝、改写、压缩**。
- `mergeClasses` 两段主开销（`copyToJavac` 162.6 s、`unpackClassesJar` 143.5 s）是逐文件目录物化的成本，与"类个数"而非"总字节"成正比。
- `cleanup` 在干净构建下归零，说明它只服务于"先删上次注入的类"这一增量幂等需求；一旦取消 javac 注入，该阶段连同逐类 SHA-1 索引一起消失。

因此本次修订的两条主线是：**取消逐层重复**（扁平聚合）与**取消逐文件目录物化**（归档级合并）。

## 非目标

- 不把所有 `implementation` 或 `api` 依赖改为嵌入依赖。
- 不改变由 `fataar.transitive` 控制的远程 POM/JAR 传递依赖解析行为。
- **非类内容同样在消费端扁平。** res / assets / jniLibs / Manifest / consumer ProGuard / 本地 `libs/*.jar` / SPI 服务 / Kotlin metadata / R.txt 符号与 class、聚合后的 DataBinding metadata 一样，由消费根逐节点采集各模块**自有**内容后合并；中间模块不再逐层继承子树内容。
  > 2026-09-21 修订：原设计选择「非类内容沿直接子模块分层继承」。实测表明该选择会让每个中间模块都必须解压整棵子树才能把后代内容烤进自己的薄产物——真实 SDK 工程干净全量下 explode 达 798 s / 222 次，占 fat-aar 耗时的 95%，其中深层节点解压出的内容 97% 被直接丢弃。改为消费端扁平后，解压总量等于全图各节点自有内容之和。
- 不改变 `fataar.transformR` 的对外语义：`true` 仍表示字节码改写 R 引用，`false` 仍表示生成别名 R 类。
- 当嵌套 fat AAR 必需时，不允许静默降级为薄 AAR。
- 不支持菱形图（两个不同直接子模块嵌入同一后代）。非类内容分层会让两条分支各自携带该后代的 res/Manifest，扁平后仍会重复；图校验继续在配置阶段拒绝。

## 核心概念：自有产物与最终产物

修订后每个应用 `com.kezong.fat-aar` 的模块对同一变体产出两个不同语义的归档：

| 产物 | 任务 | 内容 |
| --- | --- | --- |
| **薄产物**（自有产物） | AGP `bundle<Variant>Aar` | **仅本模块自身**编译的 class 与非类内容（res/assets/jniLibs/Manifest/ProGuard/libs/SPI/Kotlin metadata/R.txt 符号）+ 本模块自身的 DataBinding metadata |
| **最终产物**（fat AAR） | `reBundleAar<Variant>` | 本模块薄产物之上，把 `classes.jar` 替换为**全图自有 class 的合并结果**，并入**全图各节点自有**的非类内容与**聚合后的 DataBinding metadata** |

关键不变式：

> **薄产物只含本模块自身内容；全部后代内容由消费根在合并时逐节点取用。**
>
> 该不变式成立的前提是：模块只有在「本次构建把它当作消费根」时才展平子树（解压各节点、注入非类内容、合并 class、改写 R）。作为中间模块参与上层构建时，它不解压任何后代，其薄产物因而天然只含自身内容；上层消费根会直接从各节点自身取用。非类内容的注入（AGP sourceSet、Manifest 合并、consumer ProGuard 合并、`process<Variant>JavaRes`）在消费根改为遍历**全图节点**。
>
> 消费根由任务图判定：`<module>:reBundleAar<Variant>` 在本次构建的任务图中即为消费根。全量 `assemble` 时所有模块都在图中，退化为逐层合并（正确但慢）；显式指定聚合模块时只有它命中。

这条不变式是整个改造的地基：它让"根模块只解压直接子模块就拿到整棵子树的非类内容"，同时"根模块从全图每个节点各取一次自有 class"。

## 架构

```text
:lib-main ──embed──> :lib-aar ──embed──> :lib-aar2

根模块 :lib-main 的最终产物 =
    薄产物(:lib-main)                          ← 自有 class 与非类内容
  ⊕ class(:lib-main, :lib-aar, :lib-aar2)       ← 全图各节点自有 class，扁平一次
  ⊕ 非类内容(:lib-main, :lib-aar, :lib-aar2)    ← 自有 res/assets/jni/Manifest/proguard/libs/SPI/Kotlin，扁平一次
  ⊕ DataBinding(:lib-main, :lib-aar, :lib-aar2) ← 扁平一次
  ⊕ R 改写（全图 class 一次改写）

:lib-aar 作为中间模块参与本次构建时：不解压 :lib-aar2，薄产物只含自身内容。
:lib-aar 被单独 assemble 时：它成为消费根，按同一逻辑展平自己的子树。
```

- 消费根在配置阶段遍历完整嵌套图，得到**扁平节点列表**（后代 project + 选中变体 + 自有薄产物 + 解包目录）。
- 根模块对扁平列表里**每个节点**建立任务依赖，目标都是该节点的 `bundle<Variant>Aar`，**不再依赖任何后代的 `reBundleAar`**。
- 非类内容的 sourceSet / Manifest / assets / jniLibs / consumer ProGuard / libs / SPI / Kotlin 注入**取全部扁平节点**，各取该节点自有内容一次。
- class 合并与 DataBinding metadata 取**全部扁平节点**，各一次。
- 中间的「非消费根」模块执行期检测到自己的 `reBundleAar` 不在任务图中，因而既不建立解压任务、也不注入任何后代内容，只产出仅含自身内容的薄产物；被独立 `assemble` 时它自己是消费根，按其子树重新走一遍同一逻辑，产出内容完整的 fat AAR。

由此，`assemble` 根模块不再触发任何中间模块的 `mergeEmbeddedClasses` / `rewriteRClasses` / `reBundleAar`，这些任务只在对应模块自己被 `assemble` / 发布时执行。

## 内容来源路由

| 内容 | 取样范围 | 依据 |
| --- | --- | --- |
| `classes.jar` 条目 | 全图每个节点各一次 | 薄产物只含自身 class，必须逐节点收集 |
| DataBinding `data-binding/`、`data-binding-base-class-log/` | 全图每个节点各一次 | 聚合 metadata 只写入最终产物，薄产物只有自身 |
| `res/`、`assets/`、`jni/` | 全图每个节点各一次 | 消费根把各节点自有目录注入自己的 AGP sourceSet，由 AGP 统一合并（含 R.txt 符号生成） |
| `AndroidManifest.xml` | 全图每个节点各一次 | 消费根合并各节点自有 Manifest；非消费根模块的子节点未被解压，其 Manifest 不存在因而自动跳过 |
| `proguard.txt` | 全图每个节点各一次 | 同上 |
| `libs/*.jar` | 全图每个节点各一次 | 同上 |
| `META-INF/services/*`、`META-INF/*.kotlin_module` | 全图每个节点各一次 | 各节点自有条目在消费根合并（services 按行拼接并去重） |
| `R.txt` 符号 / 聚合 R | 全图每个节点各一次 | 符号来自消费根已注入的各节点 res |

## 类合并与 R 改写

### 取消 javac 注入

原实现把合并后的 class 目录整棵拷贝进本模块 javac 输出目录（`copyToJavac` 162.6 s），再依赖 AGP 把该目录压进 `classes.jar`，并写逐类 SHA-1 注入索引（`writeIndex` 31.0 s）以保证下次注入幂等。修订后：

- 不在 javac 目录注入任何子模块 class；`bundle<Variant>Aar` 不必再 `dependsOn/mustRunAfter` 类合并任务，薄产物因此真正"自有"。
- 删除注入索引文件、`mergeClasses.cleanup` 阶段、以及 `bundleLibRuntimeToJar` / `bundleLibCompileToJar` / `extractAnnotations` 对索引的输入跟踪。
- `mergeEmbeddedClasses<Variant>` 改为**归档级合并**：以 `Jar` 任务把「本模块薄产物 `classes.jar`（保留 `META-INF/`）」与「全图各节点薄产物 `classes.jar`（排除 `META-INF/`）」合为一个 `merged-classes.jar`。避免 `META-INF` 重复，同时保留薄产物里已分层合并完成的 SPI/Kotlin metadata。

### R 改写单遍化与预筛

原实现依赖 AGP `Transform`（`transformClassesWithTransformRFor<Variant>`），在每一层对合并后的全量 class 做 Javassist 解析 + 重写，7 层各一次（210.4 s / 22.9%）。

修订后由 `rewriteRClasses<Variant>` 在**消费根各做一次**，并跳过不含 R 引用的 class：

- 输入 `merged-classes.jar`，输出改写后的 jar；目标包为当前模块 `applicationId`。
- 源包集合不再遍历目录推导，改为从 `merged-classes.jar` 条目路径直接得出（出现即需要改写的包）。
- 逐条目处理：对 `.class` 条目先做**常量池预筛**——在原始字节里查找 `L<源包>/R$` 形式的 ASCII 子串；未命中则原样写出，命中才交给 Javassist 执行 `ConstPool.renameClass`。绝大多数 class 不引用 R，可直接跳过解析与重写。
- `fataar.transformR=false` 时跳过改写，改为对**全图每个节点**的包生成别名 R 类（现有 `RClassesGenerate` 路径扩展到扁平列表），并合入最终产物。

AGP `Transform` 不再参与打包路径；R 改写成为普通任务，输入输出可被 Gradle 增量跟踪。

### 最终产物组装

`reBundleAar<Variant>` 改为：

1. 以 `unpackBundleAar<Variant>` 解出的薄产物目录为基础；
2. `exclude 'classes.jar'`，并把 `rewriteRClasses` 的输出以 `classes.jar` 之名放回归档根；
3. 排除薄产物里自身的 `data-binding*`，并入聚合后的 DataBinding 目录。

## 任务与产物流

配置/Sync 阶段：

1. 图校验器从根变体出发 DFS 整棵嵌套图，产出**扁平节点列表**（`FlattenedEmbedNode`：后代 project、选中变体、薄产物 `bundleTask` 与输出文件、是否直接子模块），按 DFS 顺序去重。
2. 对每个节点做变体选择（沿用现有顺序：完全同名 → 同 build type → `missingDimensionStrategy` 回退），并校验插件应用情况。
3. 循环、菱形、变体冲突在配置期失败。

执行阶段（消费根）：

1. 对每个扁平节点建立解包任务（直接子模块为全量解包、深层节点可只取所需条目），输入为该节点的**薄产物**，任务依赖为该节点的 `bundle<Variant>Aar`。
4. 非类内容注入只读取 `direct = true` 的节点。
5. `unpackBundleAar<Variant>` 解出本模块薄产物。
6. `mergeEmbeddedClasses<Variant>` 归档级合并本模块与全图节点的 `classes.jar`。
7. `rewriteRClasses<Variant>` 单遍改写（`transformR = true`）。
8. `reBundleAar<Variant>` 以改写后的 `classes.jar` 与聚合 DataBinding 组装最终产物。

该流程把嵌套内容缺失、变体错配、归档不可读等问题保留在编译/构建期暴露。

## 项目图安全与身份

在建立任务依赖前，遍历适用 `embed` 配置构成的项目图：

- 维护活跃的 DFS 项目路径栈。栈中再次出现同一路径即为循环依赖，配置阶段失败并输出完整链路，例如 `:a -> :b -> :a`。
- 按"项目完整路径 + 选中变体"记录已完成节点。到达相同节点的重复边应去重，避免重复建立任务依赖和重复处理产物。
- 同一项目经不同路径被选中**不同变体**时，扁平列表无法同时容纳两份内容，配置阶段失败并给出两条冲突路径。
- 菱形图（两个不同直接子模块嵌入同一后代）继续失败：非类内容沿两条分支各自携带该后代，扁平后仍会重复。
- 项目身份必须使用完整路径和选中变体，不能仅使用模块名。
- 保持现有 Java 项目/JAR 的处理方式。非 Android 项目不进入 Android 变体选择或嵌套判断流程。

## 错误处理

配置/Sync 期失败：

- 子模块存在非空且适用的 `embed*` 配置，但没有应用 `com.kezong.fat-aar`。
- 嵌套项目图存在循环依赖。
- 同一后代项目被不同路径选中不同变体。
- 嵌套项目图为菱形（共享后代）。

构建期失败：

- 扁平节点不存在兼容的生产变体。
- 扁平节点的薄产物 `bundle<Variant>Aar` 缺失或不可读。
- 薄产物缺少 `AndroidManifest.xml`（非合法 AAR）。
- 合并后的 `classes.jar` 为空，或未包含预期节点的自有 class（完整性回归）。
- 组装后的嵌套产物缺少回归验证所要求的标记。

扁平节点不再要求后代存在 `reBundleAar`：消费路径依赖的是薄产物。中间模块的最终产物只在该模块自身被 `assemble` / 发布时构建。

## 回归测试工程

将现有 `example` 复合消费者工程保持为三级项目链：

```text
:lib-main --embed--> :lib-aar --embed--> :lib-aar2
```

- 从 `example/lib-main/build.gradle` 移除对 `:lib-aar2` 的直接 `embed`。
- 在 `example/lib-aar/build.gradle` 应用 `com.kezong.fat-aar`，并声明 `embed project(':lib-aar2', configuration: 'default')`。
- 增加分层、仅用于验证的 fixture，使 `lib-aar2` 提供独有内容，并使 `lib-aar`、`lib-main` 在需要验证的维度上具备可区分内容。
- 保留 `example/settings.gradle` 中对本地插件源码的 composite substitution。

新增两类回归：

- **独立产物回归**：`./gradlew :lib-aar:assembleFlavor1Debug` 单独执行时，`:lib-aar` 的最终 AAR 仍必须包含 `:lib-aar2` 的全部标记。
- **任务范围回归**：开启 `-PfataarDiagnostics=true` 执行 `./gradlew :lib-main:assembleFlavor1Debug`，报告里 `mergeClasses`（或 `mergeEmbeddedClasses`）、`reBundleAar`、`transformR`（或 `rewriteRClasses`）在 `:lib-aar`、`:lib-aar2` 上的执行次数必须为 **0**；这些任务只允许在 `:lib-main` 上各出现一次。

## 验证

为中间 `lib-aar` fat AAR 和最终 `lib-main` fat AAR 增加可执行验证任务。验证任务直接检查归档内容，不依赖构建日志。

针对每一层的预期内容，至少断言：

| 验证面 | 验证方式 |
| --- | --- |
| Classes | 检查 `classes.jar`，确认该层独有的 `.class` 存在。 |
| 资源 | 确认该层独有资源符号存在于 `R.txt`；最终 AAR 另由消费者编译引用。 |
| Manifest | 解析 `AndroidManifest.xml`，确认独有权限或组件已合并，且无重复条目。 |
| JNI | 确认 `jni/<abi>/` 下存在该层独有的 `.so`。 |
| Consumer ProGuard | 确认 `proguard.txt` 包含该层独有的 `-keep` 规则。 |
| DataBinding | 确认 `data-binding/` 和 `data-binding-base-class-log/` 均有该层独有 metadata。 |
| SPI | 检查 `classes.jar`，确认 `META-INF/services/<接口>` 包含各层实现，且已去重。 |
| Kotlin 元数据 | 检查 `classes.jar`，确认存在各相关层的 `META-INF/*.kotlin_module` 条目。 |

至少执行：

- 嵌套 Android 子模块通过精确 flavor 匹配的构建。
- 嵌套子模块通过 `missingDimensionStrategy` 回退匹配的构建。
- 复制最终 fat AAR 后执行 `:app:assembleDebug`，使下游 Android 消费者针对嵌套依赖中的 class 和资源完成编译。
- 上文「任务范围回归」。

性能验收（同一真实工程、干净全量构建、与基线同口径）：

| 指标 | 基线 | 目标 |
| --- | --- | --- |
| 扁平节点的 `mergeClasses` 执行次数 | 每层一次（7 次） | 每个被 assemble 的模块一次 |
| `transformR` / `rewriteRClasses` 执行次数 | 每层一次（7 次） | 每个被 assemble 的模块一次 |
| 被解包/合并/改写的 class 字节总量 | `Σ_层(子树字节)` | `Σ_节点(自有字节)` |
| fat-aar 任务合计 | 920.4 s | 预期下降 ≥ 50%，以复测为准 |

## 变更记录

| 原设计（2026-08-26） | 修订后（2026-09-18） | 依据 |
| --- | --- | --- |
| 父模块依赖子模块 `reBundleAar<Variant>`，消费子模块已完成 fat AAR | 消费根遍历全图，逐节点消费**薄产物**；不依赖后代 `reBundleAar` | 同一批类被 7 次处理，嵌套链 733.1 s / 79.6% |
| class 合并结果注入本模块 javac 目录，由 AGP 压入 `classes.jar` | `mergeEmbeddedClasses` 归档级合并，取消 javac 注入 | `copyToJavac` 162.6 s + `unpackClassesJar` 143.5 s |
| 逐类 SHA-1 注入索引 + `cleanup` 幂等删除 | 删除索引与 cleanup | `writeIndex` 31.0 s；cleanup 57.4 s 仅为增量专属 |
| R 改写走 AGP `Transform`，每层对全量 class 执行 | `rewriteRClasses` 消费根单遍执行 + 常量池预筛 | `transformR` 210.4 s / 22.9%（原判优先级偏低） |
| 解压子模块最终 fat AAR（170–320 MB） | 解压子模块薄产物；class 归档级合并 | `reBundleAar` + `unpackBundleAar` 244.0 s、`explode` 107.7 s |
| 「不在根项目扁平化整个项目图」 | 改为「class 与 DataBinding 在消费端扁平，非类内容分层」 | 中间 4 层输入体积几乎不变（171.5 → 177.4 MB） |
| class 与 DataBinding 在消费端扁平，非类内容沿直接子模块分层继承（2026-09-18） | **非类内容同样在消费端扁平**；模块仅在本次构建把它当作消费根时才展平子树，中间模块只产出仅含自身内容的薄产物（2026-09-21） | 分层继承迫使每个中间模块解压整棵子树：explode 798.3 s / 222 次，占 fat-aar 耗时的 95%，深层节点解压内容 97% 被直接丢弃（4500 MB → 141 MB） |
| 消费根判定需在配置期完成 | 用执行期任务图判定（`reBundleAar` 是否在图中），非根模块 `onlyIf` 跳过解压 + 总会执行的 reset 任务清理遗留解压产物 | `from(Closure)` 在任务图构建期求值，彼时 taskGraph 尚未填充；而残留解压产物会被 res/jniLibs 的存在性判断沿用，导致切换根/非根时不 clean 就串味 |

## 文档更新

更新中英文 README 的传递依赖章节，说明：

- 嵌套项目 `embed` 已受支持，并删除要求根模块显式声明每个本地传递项目的描述。
- Android Library 若声明 `embed` 依赖，必须应用 fat-aar 插件。
- 中间模块仍可独立 `assemble` / 发布，产物内容完整；嵌套消费路径不依赖其中间最终产物，因此构建耗时不再随嵌套深度线性放大。
