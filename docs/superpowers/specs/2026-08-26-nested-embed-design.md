# 嵌套 Embed 设计

**日期：**2026-08-26

## 目标

让 fat-aar 通过分层 fat AAR 组合支持本地项目的递归嵌入。例如：

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

## 非目标

- 不把所有 `implementation` 或 `api` 依赖改为嵌入依赖。
- 不改变由 `fataar.transitive` 控制的远程 POM/JAR 传递依赖解析行为。
- 不在根项目扁平化整个项目图，也不重复合并已经包含在子模块 fat AAR 内的后代项目。
- 当嵌套 fat AAR 必需时，不允许静默降级为薄 AAR。

## 架构

采用分层组合，而非递归扁平化 artifact：

```text
:lib-main --embed--> :lib-aar --embed--> :lib-aar2
    |                    |
    |                    +-- reBundleAar<选中变体>
    +-- 消费 :lib-aar 已完成的 fat AAR
```

每一个应用 `com.kezong.fat-aar` 且存在适用嵌入依赖的 Android Library，都先将其直接依赖打入自身的最终 fat AAR。父模块把该完整产物作为单个输入进行合并。父模块不会再次直接合并已包含在子模块 fat AAR 中的后代项目，因此 class、资源、Manifest 条目、JNI 库和元数据都只会在每一层合并一次。

现有 `VariantProcessor` 继续作为产物合并器，负责 classes/JAR、资源及 `R` 类、Manifest、assets、JNI 库、consumer ProGuard、DataBinding 元数据、SPI 服务文件和 Kotlin 元数据。嵌套 `embed` 改造只调整项目节点分类和产物任务顺序，不改变已有内容合并规则。

## 变体选择

项目依赖的生产方 Android Library 变体沿用现有选择顺序：

1. 与消费者完全同名的变体。
2. 与消费者 build type 相同的生产方变体。
3. 通过消费者 flavor 的 `missingDimensionStrategy` 选择生产方 flavor，且 build type 相同的变体。

所有嵌套节点判定均使用实际选中的生产方变体，而非根模块的变体名。这样能够支持不同 flavor 维度的嵌套模块，也能让通过回退策略选中的子模块变体被正确打包。

对于选中的生产方变体，以下配置视为适用：

- `embed`
- `<buildType>Embed`
- `<flavorName>Embed`
- `<variantName>Embed`

仅当适用配置中至少存在一个依赖时，才认定该模块声明了嵌套依赖。

## 项目节点分类

在 Gradle 配置/Sync 阶段，针对每个被嵌入的 Android 项目，依据其是否应用 `com.kezong.fat-aar` 以及其选中变体是否存在非空的适用 `embed*` 配置进行分类：

| 应用 fat-aar | 存在非空且适用的 `embed*` 配置 | 行为 |
| --- | --- | --- |
| 是 | 是 | 嵌套 fat AAR 节点。父模块依赖子模块选中变体的 `reBundleAar<Variant>` 任务，并消费该任务产生的最终 AAR。 |
| 是 | 否 | 普通 Android Library。保持当前薄 AAR 合并流程。 |
| 否 | 否 | 普通 Android Library。保持当前薄 AAR 合并流程。 |
| 否 | 是 | 在配置/Sync 阶段失败。该项目声明了 `embed`，但未应用 fat-aar 插件，因而无法将该依赖打入自身 AAR。错误必须指出子项目路径和命中的配置，并建议应用 `com.kezong.fat-aar`，或按实际语义改用 `implementation`/`api`。 |

第一行属于正常嵌套处理流程，绝不产生上述配置错误。

## 任务与产物流

1. 父模块使用现有 flavor artifact 逻辑解析本地 `embed` 项目的真实生产产物和选中变体。
2. 对普通 Android Library，保留当前对常规 bundle 任务的依赖关系。
3. 对嵌套 fat AAR 节点，在配置阶段定位子模块选中变体的 `reBundleAar<Variant>` 任务。父模块的解压/消费链必须依赖该任务，而非仅依赖原始 `bundle<Variant>Aar` 任务。
4. 子模块重打包任务将完整 fat AAR 写入所选 bundle AAR 的输出路径。父模块随后解压该最终输出，并交由现有合并器处理。
5. 嵌套节点无法选择生产变体、找不到 `reBundleAar<Variant>`，或执行时未产生可消费的最终 AAR，均应使构建失败。错误需包含父项目、子项目、请求变体、选中变体以及缺失的任务或产物信息。

该流程将嵌套内容缺失转为编译/构建期错误，而不是推迟到运行时才暴露。

## 项目图安全与身份

在建立任务依赖前，遍历适用 `embed` 配置构成的项目图：

- 维护活跃的 DFS 项目路径栈。栈中再次出现同一路径即为循环依赖，配置阶段失败并输出完整链路，例如 `:a -> :b -> :a`。
- 按“项目完整路径 + 选中变体”记录已完成节点。到达相同节点的重复边应去重，避免重复建立任务依赖和重复处理产物。
- 项目身份必须使用完整路径和选中变体，不能仅使用模块名，避免不同项目或变体产生冲突。
- 保持现有 Java 项目/JAR 的处理方式。非 Android 项目不进入 Android 变体选择或嵌套 fat AAR 判断流程。

## 错误处理

配置/Sync 期失败：

- 子模块存在非空且适用的 `embed*` 配置，但没有应用 `com.kezong.fat-aar`。
- 嵌套项目图存在循环依赖。

构建期失败：

- 嵌套 fat AAR 子模块不存在兼容的生产变体。
- 选中子模块的 `reBundleAar<Variant>` 任务不存在。
- 该任务未产生可消费的最终 AAR。
- 组装后的嵌套产物缺少回归验证所要求的标记。

嵌套 fat AAR 节点不存在薄 AAR 回退路径。普通 Android Library 因没有待传递的嵌套内容，继续使用既有薄 AAR 合并路径。

## 回归测试工程

将现有 `example` 复合消费者工程扩展为真实的三级项目链：

```text
:lib-main --embed--> :lib-aar --embed--> :lib-aar2
```

- 从 `example/lib-main/build.gradle` 移除对 `:lib-aar2` 的直接 `embed`。
- 在 `example/lib-aar/build.gradle` 应用 `com.kezong.fat-aar`，并声明 `embed project(':lib-aar2', configuration: 'default')`。
- 增加分层、仅用于验证的 fixture，使 `lib-aar2` 提供独有内容，并使 `lib-aar`、`lib-main` 在需要验证的维度上具备可区分内容。
- 保留 `example/settings.gradle` 中对本地插件源码的 composite substitution，确保构建直接使用 `source` 中的插件实现。

## 验证

为中间 `lib-aar` fat AAR 和最终 `lib-main` fat AAR 增加可执行验证任务。验证任务直接检查归档内容，不依赖构建日志。

针对每一层的预期内容，至少断言：

| 验证面 | 验证方式 |
| --- | --- |
| Classes | 检查 `classes.jar`，确认该层独有的 `.class` 存在。 |
| 资源 | 确认该层独有资源符号存在于 `R.txt`；最终 AAR 另由消费者编译引用。 |
| Manifest | 解析 `AndroidManifest.xml`，确认独有权限或组件已合并。 |
| JNI | 确认 `jni/<abi>/` 下存在该层独有的 `.so`。 |
| Consumer ProGuard | 确认 `proguard.txt` 包含该层独有的 `-keep` 规则。 |
| DataBinding | 确认 `data-binding/` 和 `data-binding-base-class-log/` 均有该层独有 metadata。 |
| SPI | 检查 `classes.jar`，确认 `META-INF/services/<接口>` 包含各层实现，且已去重。 |
| Kotlin 元数据 | 检查 `classes.jar`，确认存在各相关层的 `META-INF/*.kotlin_module` 条目。 |

至少执行：

- 嵌套 Android 子模块通过精确 flavor 匹配的构建。
- 嵌套子模块通过 `missingDimensionStrategy` 回退匹配的构建。
- 复制最终 fat AAR 后执行 `:app:assembleDebug`，使下游 Android 消费者针对嵌套依赖中的 class 和资源完成编译。

任一标记缺失都必须使验证任务失败。即使普通 AAR 组装本身没有发现遗漏，也能在构建期获得明确反馈。

## 文档更新

更新中英文 README 的传递依赖章节，说明嵌套项目 `embed` 已受支持，并删除要求根模块显式声明每个本地传递项目的描述。同时说明：Android Library 若声明 `embed` 依赖，必须应用 fat-aar 插件。
