# 嵌套 Embed 支持实施计划

> **面向执行型代理：**必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 子技能，逐任务实施本计划。每个步骤均以复选框追踪。

**目标：**让 fat-aar 以分层 fat AAR 组合方式支持任意深度的本地 Android 项目 `embed` 链，并在构建期验证嵌套产物完整性。

**架构：**子 Android Library 同时应用 fat-aar 且存在当前变体适用的非空 `embed*` 配置时，父模块把它识别为嵌套 fat AAR 节点，并依赖该子模块 `reBundleAar<Variant>` 的输出；父模块不再直接展开其后代依赖。项目节点分类和循环检测发生在配置/Sync 期，内容合并继续由现有 `VariantProcessor` 管理。

**技术栈：**Gradle Groovy 插件、Android Gradle Plugin 4.2 API、Gradle `LibraryVariant`、ZipFile、现有示例复合构建（AGP 7.0.2）、Java/Kotlin、DataBinding。

---

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `source/src/main/groovy/com/kezong/fataar/FlavorArtifact.groovy` | 暴露本地 Android 生产方变体选择结果，供薄 AAR 与嵌套 fat AAR 路径共用。 |
| `source/src/main/groovy/com/kezong/fataar/NestedEmbedNode.groovy` | 保存嵌套节点的父/子项目、请求/选中变体、最终任务和产物信息。 |
| `source/src/main/groovy/com/kezong/fataar/NestedEmbedGraphValidator.groovy` | 在配置/Sync 期分类节点、检测未应用插件的 `embed` 声明和循环依赖。 |
| `source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy` | 统一适用 `embed` 配置选择，调用图校验器，并将嵌套节点传给变体处理器。 |
| `source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy` | 让父模块的 AAR 解压任务依赖子模块最终 `reBundleAar`，并在执行期校验最终 AAR。 |
| `example/lib-aar/build.gradle` | 将中间模块改为 fat-aar 节点，并提供中间产物验证任务。 |
| `example/lib-main/build.gradle` | 移除直接孙模块嵌入，并提供最终产物验证和拷贝任务。 |
| `example/lib-aar2/build.gradle` | 开启 Kotlin/DataBinding，提供下层 JNI、ProGuard、DataBinding 与 Kotlin 验证内容。 |
| `example/lib-aar*/src/main/...` | 提供各类归档验证标记：class、资源、Manifest、JNI、ProGuard、DataBinding、SPI、Kotlin。 |
| `example/app/build.gradle` | 把应用构建串联到最终 AAR 验证之后。 |
| `README.md`、`README_CN.md` | 说明嵌套项目 `embed` 的语义和子模块插件要求。 |

本次不支持菱形嵌套图（两个不同的直接子模块均把同一个后代打入自身 fat AAR）。遍历去重只能避免重复注册任务，无法从两个完成的 AAR 中剥离重复内容；实现中应在图校验阶段明确检测并以构建期错误拒绝该形态。

### 任务 1：为生产方变体选择建立可复用结果

**文件：**
- 修改：`source/src/main/groovy/com/kezong/fataar/FlavorArtifact.groovy:35-188`
- 新增：`source/src/main/groovy/com/kezong/fataar/SelectedVariantArtifact.groovy`

- [ ] **步骤 1：定义选择结果对象**

新增 `SelectedVariantArtifact.groovy`，使后续逻辑不再只依赖合成的 `ResolvedArtifact`：

```groovy
package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

class SelectedVariantArtifact {
    final Project project
    final LibraryVariant variant
    final TaskProvider bundleTask
    final File outputFile

    SelectedVariantArtifact(Project project, LibraryVariant variant,
                            TaskProvider bundleTask, File outputFile) {
        this.project = project
        this.variant = variant
        this.bundleTask = bundleTask
        this.outputFile = outputFile
    }
}
```

- [ ] **步骤 2：将现有三段变体匹配逻辑提取为选择器**

在 `FlavorArtifact.groovy` 增加 `static SelectedVariantArtifact selectVariantArtifact(Project producer, LibraryVariant consumerVariant)`。它必须遍历 `producer.android.libraryVariants` 并保持原有顺序：完全同名、同 build type、`missingDimensionStrategy` 的 flavor 回退。匹配到 `LibraryVariant selected` 后，调用 `VersionAdapter.getBundleTaskProvider(producer, selected.name)`，并用现有 `createArtifactFile(producer, bundleTask.get())` 取得输出文件：

```groovy
static SelectedVariantArtifact selectVariantArtifact(Project producer, LibraryVariant consumerVariant) {
    LibraryVariant selected = producer.android.libraryVariants.find { candidate ->
        candidate.name == consumerVariant.name
    }
    if (selected == null) {
        selected = producer.android.libraryVariants.find { candidate ->
            candidate.name == consumerVariant.buildType.name
        }
    }
    if (selected == null) {
        def consumerFlavor = consumerVariant.productFlavors.isEmpty() ?
                consumerVariant.mergedFlavor : consumerVariant.productFlavors.first()
        selected = producer.android.libraryVariants.find { candidate ->
            def candidateFlavor = candidate.productFlavors.isEmpty() ?
                    candidate.mergedFlavor : candidate.productFlavors.first()
            consumerFlavor.missingDimensionStrategies.any { dimension, strategy ->
                dimension == candidateFlavor.dimension &&
                        strategy.fallbacks.contains(candidateFlavor.name) &&
                        candidate.buildType.name == consumerVariant.buildType.name
            }
        }
    }
    if (selected == null) {
        return null
    }
    TaskProvider bundleTask = VersionAdapter.getBundleTaskProvider(producer, selected.name)
    return new SelectedVariantArtifact(producer, selected, bundleTask,
            createArtifactFile(producer, bundleTask.get()))
}
```

- [ ] **步骤 3：使薄 AAR 的 fallback 使用同一选择器**

将 `createFlavorArtifact` 改为先调用 `selectVariantArtifact`，为空时记录原有错误并返回 `null`；以 `selection.bundleTask` 与 `selection.outputFile` 创建合成 artifact。将 `getArtifactProject` 改为接受可选 `ProjectDependency` 的 `dependencyProject`，或至少以项目完整 `path` 精确匹配，禁止只按 `name` 选择项目。

- [ ] **步骤 4：构建插件并确认现有示例的变体选择未退化**

运行：

```bash
./gradlew :source:build
cd example && ./gradlew :lib-main:assembleFlavor1Debug --stacktrace
```

预期：两个命令均成功；在现有行为未切换嵌套任务前，`lib-main` 仍可选择 `lib-aar` 的 `flavor1Debug` 和 `lib-aar2` 的 fallback 变体。

- [ ] **步骤 5：提交本任务的源代码变更**

```bash
git add source/src/main/groovy/com/kezong/fataar/FlavorArtifact.groovy \
  source/src/main/groovy/com/kezong/fataar/SelectedVariantArtifact.groovy
git commit -m "refactor: expose embedded project variant selection"
```

### 任务 2：实现嵌套节点模型与配置期图校验

**文件：**
- 新增：`source/src/main/groovy/com/kezong/fataar/NestedEmbedNode.groovy`
- 新增：`source/src/main/groovy/com/kezong/fataar/NestedEmbedGraphValidator.groovy`
- 修改：`source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy:65-110`

- [ ] **步骤 1：定义嵌套节点模型**

新增 `NestedEmbedNode.groovy`：

```groovy
package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

class NestedEmbedNode {
    final Project parentProject
    final Project childProject
    final LibraryVariant requestedVariant
    final SelectedVariantArtifact selection
    final TaskProvider reBundleTask

    NestedEmbedNode(Project parentProject, Project childProject,
                    LibraryVariant requestedVariant,
                    SelectedVariantArtifact selection, TaskProvider reBundleTask) {
        this.parentProject = parentProject
        this.childProject = childProject
        this.requestedVariant = requestedVariant
        this.selection = selection
        this.reBundleTask = reBundleTask
    }

    String getKey() {
        return "${childProject.path}@${selection.variant.name}"
    }
}
```

- [ ] **步骤 2：在插件入口统一“适用配置”语义**

在 `FatAarPlugin.groovy` 添加包内可访问静态方法：

```groovy
static Collection<Configuration> getApplicableEmbedConfigurations(Project project, LibraryVariant variant) {
    LinkedHashSet<String> names = new LinkedHashSet<>()
    names.add(CONFIG_NAME)
    names.add(variant.buildType.name + CONFIG_SUFFIX)
    if (variant.flavorName != null && !variant.flavorName.isEmpty()) {
        names.add(variant.flavorName + CONFIG_SUFFIX)
    }
    names.add(variant.name + CONFIG_SUFFIX)
    return names.collect { project.configurations.findByName(it) }
            .findAll { it != null }
}

static Collection<Configuration> getNonEmptyApplicableEmbedConfigurations(Project project, LibraryVariant variant) {
    return getApplicableEmbedConfigurations(project, variant)
            .findAll { !it.dependencies.empty }
}
```

将 `doAfterEvaluate` 中根据配置名称的两段 if 条件替换为 `getApplicableEmbedConfigurations(project, variant)` 的调用，保证根项目 artifact 收集、子模块分类和图校验拥有相同的范围。

- [ ] **步骤 3：实现图校验器与节点判定**

新增 `NestedEmbedGraphValidator.groovy`。它接收根项目和根 `LibraryVariant`，从该变体适用配置中的 `ProjectDependency` 开始 DFS。对 Android 子项目：

```groovy
private NestedEmbedNode inspect(Project parent, Project child,
                                LibraryVariant requestedVariant,
                                List<String> activePath) {
    SelectedVariantArtifact selection = FlavorArtifact.selectVariantArtifact(child, requestedVariant)
    if (selection == null) {
        throw new ProjectConfigurationException(
                "嵌套 embed 无法匹配变体：父项目 ${parent.path}，子项目 ${child.path}，请求变体 ${requestedVariant.name}", null)
    }
    Collection<Configuration> configs = FatAarPlugin
            .getNonEmptyApplicableEmbedConfigurations(child, selection.variant)
    boolean usesFatAar = child.plugins.hasPlugin('com.kezong.fat-aar')
    if (!usesFatAar && !configs.empty) {
        throw new ProjectConfigurationException(
                "子项目 ${child.path} 的变体 ${selection.variant.name} 声明了 ${configs*.name.join(', ')}，" +
                "但未应用 com.kezong.fat-aar。请应用 com.kezong.fat-aar，或按实际语义改用 implementation/api。", null)
    }
    if (!usesFatAar || configs.empty) {
        return null
    }
    String taskName = "reBundleAar${selection.variant.name.capitalize()}"
    TaskProvider reBundleTask = child.tasks.named(taskName)
    return new NestedEmbedNode(parent, child, requestedVariant, selection, reBundleTask)
}
```

在进入子节点前检查 `activePath.contains(child.path)`；存在时抛出 `ProjectConfigurationException("检测到嵌套 embed 循环：${(activePath + child.path).join(' -> ')}", null)`。以 `node.key` 缓存已经完成的节点，避免重复遍历同一“项目路径 + 变体”。

对于两个不同直接分支产出的 nested node 指向相同后代 key，抛出 `ProjectConfigurationException`，说明分层 AAR 组合不支持共享后代，以避免最终 AAR 重复 class 和资源。

- [ ] **步骤 4：在正确生命周期执行校验并保存根节点映射**

在 `FatAarPlugin.apply` 中注册：

```groovy
project.gradle.projectsEvaluated {
    project.android.libraryVariants.all { variant ->
        nestedEmbedNodesByVariant.put(variant.name,
                new NestedEmbedGraphValidator(project, variant).validate())
    }
}
```

新增字段 `Map<String, Collection<NestedEmbedNode>> nestedEmbedNodesByVariant = new HashMap<>()`。`projectsEvaluated` 确保所有子项目已完成 `afterEvaluate` 并创建 `reBundleAar<Variant>`，不会因评估顺序将正常嵌套节点误判为任务缺失。

- [ ] **步骤 5：运行负向配置期验证**

在 `example` 下新增两个独立目录：

```text
example/fixtures/no-plugin-embed/
example/fixtures/cyclic-embed/
```

每个 fixture 使用最小 `settings.gradle` 与 `build.gradle`，通过 `includeBuild('../../source')` 使用本地插件。第一个 fixture 的 Android 子模块只定义 `configurations.create('embed')` 并添加项目依赖、不应用 fat-aar；第二个 fixture 由 `:a` 与 `:b` 都应用 fat-aar 并相互 `embed`。运行：

```bash
cd example/fixtures/no-plugin-embed && ../../gradlew help --stacktrace
cd example/fixtures/cyclic-embed && ../../gradlew help --stacktrace
```

预期：第一个命令失败且输出子项目路径、命中 `embed` 配置和 `com.kezong.fat-aar`；第二个命令失败且输出完整 `:a -> :b -> :a` 链路。

- [ ] **步骤 6：提交本任务的代码与负向 fixture**

```bash
git add source/src/main/groovy/com/kezong/fataar/NestedEmbedNode.groovy \
  source/src/main/groovy/com/kezong/fataar/NestedEmbedGraphValidator.groovy \
  source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy \
  example/fixtures
git commit -m "feat: validate nested embed project graph"
```

### 任务 3：让父模块消费子模块最终 fat AAR

**文件：**
- 修改：`source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy:72-109`
- 修改：`source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy:143-198`
- 修改：`source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy:356-425`

- [ ] **步骤 1：将当前变体的嵌套节点传给 `VariantProcessor`**

将构造函数改为：

```groovy
VariantProcessor(Project project, LibraryVariant variant,
                 Map<String, Project> embedProjectsMap,
                 Collection<NestedEmbedNode> nestedEmbedNodes) {
    mProject = project
    mVariant = variant
    mVersionAdapter = new VersionAdapter(project, variant)
    mEmbedProjectsMap = embedProjectsMap ?: new HashMap<>()
    mNestedEmbedNodes = nestedEmbedNodes ?: new ArrayList<>()
}
```

新增字段 `private Collection<NestedEmbedNode> mNestedEmbedNodes`，并在 `FatAarPlugin.doAfterEvaluate` 中传入 `nestedEmbedNodesByVariant.get(variant.name)`。

- [ ] **步骤 2：保证直接执行 reBundle 也会先执行 bundle**

在 `VariantProcessor.configureReBundleAarTask` 创建 `Zip` 任务后建立显式依赖：

```groovy
TaskProvider task = mProject.getTasks().register(taskName, Zip.class) { /* 现有配置 */ }
task.configure {
    dependsOn(bundleTask)
}
```

保留已有 `bundleTask.configure { finalizedBy(reBundleTask) }`。这样执行 `bundle...Aar` 与执行 `reBundleAar...` 两种入口都会获得最终 fat AAR。

- [ ] **步骤 3：按产物来源定位嵌套节点**

在 `VariantProcessor` 添加：

```groovy
private NestedEmbedNode findNestedNode(ResolvedArtifact artifact, Project embedProject) {
    return mNestedEmbedNodes.find { node ->
        node.childProject == embedProject &&
                node.selection.outputFile.absolutePath == artifact.file.absolutePath
    }
}
```

对于 `FlavorArtifact` 生成的 artifact，优先按照 `embedProject.path` 和 `node.selection.variant.name` 匹配；若 Gradle artifact 的实际文件尚未存在，不得依据文件内容判断节点。

- [ ] **步骤 4：让解压任务依赖子模块 reBundle，并禁止降级**

在 `processArtifacts` 创建 `explodeTask` 后，替换现有只从 `artifact.buildDependencies` 取第一项的逻辑：

```groovy
NestedEmbedNode nestedNode = findNestedNode(artifact, embedProj)
if (nestedNode != null) {
    explodeTask.dependsOn(nestedNode.reBundleTask)
    explodeTask.doFirst {
        File output = nestedNode.selection.outputFile
        if (!output.isFile() || !output.canRead()) {
            throw new GradleException(
                    "嵌套 fat AAR 不可用：父项目 ${mProject.path}，子项目 ${nestedNode.childProject.path}，" +
                    "请求变体 ${nestedNode.requestedVariant.name}，选中变体 ${nestedNode.selection.variant.name}，" +
                    "任务 reBundleAar${nestedNode.selection.variant.name.capitalize()} 未生成可读产物 ${output}")
        }
    }
} else if (!dependencies.empty) {
    explodeTask.dependsOn(dependencies)
} else {
    explodeTask.dependsOn(prepareTask)
}
```

这里的普通分支必须依赖完整 `dependencies` 集合，而不是 `dependencies.first()`。嵌套节点永远不回退为 `bundle` 任务。

- [ ] **步骤 5：运行中间层嵌套构建**

临时将示例修改为 `lib-aar -> lib-aar2` 后运行：

```bash
cd example && ./gradlew :lib-aar:reBundleAarFlavor1Debug --stacktrace
```

预期：成功；日志中既出现 `:lib-aar2` 的解压任务，也能确认 `reBundleAarFlavor3Debug` 先于 `:lib-aar` 的解压任务执行。

- [ ] **步骤 6：提交本任务的源代码变更**

```bash
git add source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy \
  source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy
git commit -m "feat: compose nested fat aar outputs"
```

### 任务 4：建立三级嵌套示例与归档标记

**文件：**
- 修改：`example/lib-main/build.gradle:65-103`
- 修改：`example/lib-aar/build.gradle:1-49`
- 修改：`example/lib-aar2/build.gradle:1-49`
- 修改：`example/lib-aar2/proguard-rules.pro`
- 新增：`example/lib-aar2/src/main/jniLibs/armeabi-v7a/libnested_embed_marker.so`
- 新增：`example/lib-aar2/src/main/res/layout/nested_embed_binding.xml`
- 新增：`example/lib-aar2/src/main/java/com/kezong/demo/libaar2/NestedEmbedKotlinMarker.kt`
- 新增：`example/lib-aar/src/main/java/com/kezong/demo/libaar/spi/NestedEmbedService.java`
- 新增：`example/lib-aar/src/main/java/com/kezong/demo/libaar/spi/LibAarService.java`
- 新增：`example/lib-aar/src/main/resources/META-INF/services/com.kezong.demo.libaar.spi.NestedEmbedService`
- 新增：`example/lib-aar2/src/main/java/com/kezong/demo/libaar/spi/LibAar2Service.java`
- 新增：`example/lib-aar2/src/main/resources/META-INF/services/com.kezong.demo.libaar.spi.NestedEmbedService`

- [ ] **步骤 1：改造三级依赖链与变体回退链**

在 `example/lib-main/build.gradle` 删除：

```groovy
embed project(path: ':lib-aar2', configuration: 'default')
```

保留 `embed project(path: ':lib-aar', configuration: 'default')`。

在 `example/lib-aar/build.gradle` 紧随 Android 插件添加：

```groovy
apply plugin: 'com.kezong.fat-aar'
```

并新增依赖：

```groovy
dependencies {
    implementation fileTree(dir: 'libs', include: '*.jar')
    embed project(path: ':lib-aar2', configuration: 'default')
}
```

为 `lib-aar` 的 `flavor1` 和 `flavor2` 添加：

```groovy
missingDimensionStrategy 'default2', 'flavor3'
```

保留 `lib-aar2` 的 `default2/flavor3`，使 `lib-main:flavor1Debug -> lib-aar:flavor1Debug` 走精确匹配，而 `lib-aar:flavor1Debug -> lib-aar2:flavor3Debug` 走 fallback。

- [ ] **步骤 2：补齐下层 JNI、ProGuard、DataBinding 与 Kotlin 标记**

在 `example/lib-aar2/build.gradle` 添加：

```groovy
apply plugin: 'kotlin-android'

android {
    buildFeatures {
        dataBinding = true
    }
}
```

在 `proguard-rules.pro` 添加：

```pro
-keep class com.kezong.demo.libaar2.Aar2LibClass { *; }
-keep class com.kezong.demo.libaar2.NestedEmbedKotlinMarker { *; }
```

新增 `NestedEmbedKotlinMarker.kt`：

```kotlin
package com.kezong.demo.libaar2

object NestedEmbedKotlinMarker {
    const val VALUE = "nested-embed-kotlin"
}
```

新增 `nested_embed_binding.xml`：

```xml
<layout xmlns:android="http://schemas.android.com/apk/res/android">
    <data />
    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="nested-embed-binding" />
</layout>
```

使用有效的、最小的 ELF `.so` fixture 作为 `libnested_embed_marker.so`；执行 `file` 或 `readelf -h` 验证它是当前构建 ABI 可被 AGP 打包的 ELF 文件，不能提交空文件或文本伪文件。

- [ ] **步骤 3：建立可验证的 SPI 服务合并内容**

新增服务接口：

```java
package com.kezong.demo.libaar.spi;

public interface NestedEmbedService {
    String source();
}
```

`LibAarService.java`：

```java
package com.kezong.demo.libaar.spi;

public class LibAarService implements NestedEmbedService {
    @Override
    public String source() {
        return "lib-aar";
    }
}
```

`LibAar2Service.java`：

```java
package com.kezong.demo.libaar.spi;

public class LibAar2Service implements NestedEmbedService {
    @Override
    public String source() {
        return "lib-aar2";
    }
}
```

服务声明文件分别写入一行全限定名：

```text
com.kezong.demo.libaar.spi.LibAarService
```

```text
com.kezong.demo.libaar.spi.LibAar2Service
```

- [ ] **步骤 4：执行中间层构建并确认失败前置测试**

先不实现任务 3 的 reBundle 依赖改动，只修改三级 fixture 后运行：

```bash
cd example && ./gradlew :lib-aar:assembleFlavor1Debug --stacktrace
```

预期：产物验证（将在任务 5 新增）尚不存在；人工检查薄 AAR 不应包含 `lib-aar2` 的 class、资源和 JNI 标记。这是后续嵌套行为的失败基线。完成任务 3 后再次运行相同命令，产物应包含所有标记。

- [ ] **步骤 5：提交三级 fixture 与标记文件**

```bash
git add example/lib-main example/lib-aar example/lib-aar2
git commit -m "test: add nested fat aar integration fixture"
```

### 任务 5：为中间与最终 AAR 添加可执行归档验证

**文件：**
- 修改：`example/lib-aar/build.gradle`
- 修改：`example/lib-main/build.gradle`
- 修改：`example/app/build.gradle`

- [ ] **步骤 1：在 `lib-aar` 添加 AAR 内容验证任务**

在 `example/lib-aar/build.gradle` 新增函数。它使用 `java.util.zip.ZipFile` 打开 AAR，再从临时文件读取 `classes.jar`，不依赖任务日志：

```groovy
import java.util.zip.ZipFile

void assertAarEntry(ZipFile aar, String entryName) {
    if (aar.getEntry(entryName) == null) {
        throw new GradleException("AAR 缺少条目: ${entryName}")
    }
}

tasks.register('verifyNestedFatAarFlavor1Debug') {
    dependsOn tasks.named('reBundleAarFlavor1Debug')
    doLast {
        File aarFile = tasks.named('bundleFlavor1DebugAar').get().archiveFile.get().asFile
        File classesJar = file("${buildDir}/tmp/verifyNestedFatAarFlavor1Debug/classes.jar")
        classesJar.parentFile.mkdirs()
        new ZipFile(aarFile).withCloseable { aar ->
            assertAarEntry(aar, 'jni/armeabi-v7a/libnested_embed_marker.so')
            assertAarEntry(aar, 'proguard.txt')
            assertAarEntry(aar, 'data-binding/')
            assertAarEntry(aar, 'data-binding-base-class-log/')
            assertAarEntry(aar, 'R.txt')
            classesJar.bytes = aar.getInputStream(aar.getEntry('classes.jar')).bytes
            String manifest = aar.getInputStream(aar.getEntry('AndroidManifest.xml')).getText('UTF-8')
            assert manifest.contains('android.permission.ACCESS_NETWORK_STATE')
            String proguard = aar.getInputStream(aar.getEntry('proguard.txt')).getText('UTF-8')
            assert proguard.contains('com.kezong.demo.libaar2.NestedEmbedKotlinMarker')
            String rText = aar.getInputStream(aar.getEntry('R.txt')).getText('UTF-8')
            assert rText.contains('app_name_aar2')
        }
        new ZipFile(classesJar).withCloseable { classes ->
            assert classes.getEntry('com/kezong/demo/libaar2/Aar2LibClass.class') != null
            assert classes.getEntry('com/kezong/demo/libaar2/NestedEmbedKotlinMarker.class') != null
            assert classes.entries().any { it.name.startsWith('META-INF/') && it.name.endsWith('.kotlin_module') }
            String services = classes.getInputStream(classes.getEntry(
                    'META-INF/services/com.kezong.demo.libaar.spi.NestedEmbedService')).getText('UTF-8')
            assert services.readLines().count('com.kezong.demo.libaar.spi.LibAarService') == 1
            assert services.readLines().count('com.kezong.demo.libaar.spi.LibAar2Service') == 1
        }
    }
}
```

将 `data-binding/` 目录断言改为以 `aar.entries().any { it.name.startsWith('data-binding/') }` 实现，因为 ZIP 中目录条目不保证存在；同样处理 `data-binding-base-class-log/`。

- [ ] **步骤 2：在 `lib-main` 复用相同验证策略并检查全部三层标记**

在 `example/lib-main/build.gradle` 新增 `verifyNestedFatAarFlavor1Debug`，依赖 `reBundleAarFlavor1Debug`，读取最终 AAR 后断言：

```groovy
assert classes.getEntry('com/kezong/demo/lib/MainLibClass.class') != null
assert classes.getEntry('com/kezong/demo/libaar/AarLibClass.class') != null
assert classes.getEntry('com/kezong/demo/libaar2/Aar2LibClass.class') != null
assert manifest.contains('android.permission.BLUETOOTH')
assert manifest.contains('android.permission.ACCESS_NETWORK_STATE')
assert rText.contains('app_name_aar')
assert rText.contains('app_name_aar2')
assert proguard.contains('com.kezong.demo.libaar2.NestedEmbedKotlinMarker')
assert aar.entries().any { it.name == 'jni/armeabi-v7a/libnested_embed_marker.so' }
```

对 DataBinding 目录使用 `startsWith` 条目检查，对 SPI 服务执行与步骤 1 相同的双实现且每个仅一次的检查，对 Kotlin metadata 检查至少一个来自 `lib-aar2` 的唯一 `.kotlin_module` 文件名。通过任务实际打印已检查 AAR 绝对路径与检查项计数。

- [ ] **步骤 3：将应用编译串联到最终 AAR 验证**

在 `example/app/build.gradle` 添加：

```groovy
tasks.named('preBuild').configure {
    dependsOn gradle.includedBuild('source').task(':build')
    dependsOn ':lib-main:verifyNestedFatAarFlavor1Debug'
}
```

在 `lib-main` 的 `afterEvaluate` 复制动作之前，让 `assembleFlavor1Debug` 依赖 `verifyNestedFatAarFlavor1Debug`，并保留将最终 AAR 拷贝为 `example/app/libs/fat-aar-final.aar` 的现有逻辑。避免把 `verify` 任务反向依赖 `assemble`，否则会形成 task cycle。

- [ ] **步骤 4：先运行中间产物断言，确认其通过**

运行：

```bash
cd example && ./gradlew :lib-aar:verifyNestedFatAarFlavor1Debug --stacktrace
```

预期：成功；输出表明 AAR 同时含 `lib-aar` 与 `lib-aar2` 的 class、资源、Manifest、JNI、ProGuard、DataBinding、SPI/Kotlin metadata 标记。

- [ ] **步骤 5：运行最终产物与消费者断言**

运行：

```bash
cd example && ./gradlew :lib-main:verifyNestedFatAarFlavor1Debug :app:assembleDebug --stacktrace
```

预期：两个任务成功；`MainActivity.java` 对 `Aar2LibClass` 与 `R.string.app_name_aar2` 的引用能编译，证明最终发布 AAR 对下游应用暴露嵌套 class 与资源。

- [ ] **步骤 6：执行 fallback 变体验证**

运行：

```bash
cd example && ./gradlew :lib-aar:verifyNestedFatAarFlavor2Debug :lib-main:verifyNestedFatAarFlavor2Debug --stacktrace
```

为 `flavor2Debug` 注册与步骤 1/2 相同的断言任务。预期：成功；任务日志应显示 `lib-aar2` 被选为 `flavor3Debug`，验证 `missingDimensionStrategy` 对嵌套节点仍生效。

- [ ] **步骤 7：提交验证任务与应用串联变更**

```bash
git add example/lib-aar/build.gradle example/lib-main/build.gradle example/app/build.gradle
git commit -m "test: verify nested fat aar archive contents"
```

### 任务 6：更新用户文档并完成全量验证

**文件：**
- 修改：`README.md:64-92`
- 修改：`README_CN.md:75-107`

- [ ] **步骤 1：更新英文 README 的本地传递依赖说明**

将现有“根模块必须为所有本地传递依赖声明 `embed`”替换为：

```markdown
#### Local Project Dependency

Nested local project embedding is supported. Each Android library that declares an `embed` dependency must also apply `com.kezong.fat-aar`.

For example, `mainLib` can embed `subLib1`, and `subLib1` can embed `subLib2`. When each embedding library applies the plugin, the final `mainLib` AAR includes all three libraries. Do not repeat `subLib2` in `mainLib`; doing so would merge it twice.

A project that declares `embed` without applying `com.kezong.fat-aar` fails during Gradle configuration. Cyclic or shared-descendant project graphs are rejected because layered fat AAR composition cannot safely merge the same completed descendant AAR twice.
```

- [ ] **步骤 2：更新中文 README 的本地多级依赖说明**

将 `README_CN.md` 第 77-81 行替换为：

```markdown
#### 本地项目依赖

支持嵌套本地项目 `embed`。任意 Android Library 只要声明了 `embed` 依赖，就必须同时应用 `com.kezong.fat-aar`。

例如 `mainLib` 可以 `embed lib1`，而 `lib1` 可以 `embed lib2`。当每个声明 `embed` 的模块均应用插件时，最终 `mainLib` AAR 会包含三者内容；不要在 `mainLib` 中重复 `embed lib2`，否则会导致内容重复合并。

未应用插件却声明 `embed` 的项目会在 Gradle 配置阶段失败。循环依赖以及多个分支共享同一后代项目的图会被拒绝，因为分层 fat AAR 组合无法安全地合并两份已包含相同后代内容的完成产物。
```

保留远程依赖章节及 `fataar.transitive` 的原有语义，不将其描述为本地嵌套项目的开关。

- [ ] **步骤 3：执行全部构建和文档质量检查**

运行：

```bash
./gradlew :source:build
cd example && ./gradlew \
  :lib-aar:verifyNestedFatAarFlavor1Debug \
  :lib-aar:verifyNestedFatAarFlavor2Debug \
  :lib-main:verifyNestedFatAarFlavor1Debug \
  :lib-main:verifyNestedFatAarFlavor2Debug \
  :app:assembleDebug --stacktrace
cd .. && git diff --check
```

预期：全部构建和验证任务成功；`git diff --check` 无空白错误。

- [ ] **步骤 4：检查工作区与提交文档改动**

```bash
git status --short
git add README.md README_CN.md
git commit -m "docs: explain nested local project embedding"
```

预期：只提交本任务相关 README 文件；不要将用户已有的 `settings.gradle`、`HTProtectLib/`、`miyu-sdk/`、`opensdk-lite/`、`tbslib/` 或其他无关未跟踪内容纳入提交。
