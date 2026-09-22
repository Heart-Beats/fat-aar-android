# 嵌套 Embed 扁平聚合实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把嵌套 Embed 的打包耗时从 `O(深度 × 子树内容)` 降到 `O(全图自有内容)`，同时保持每个中间模块都能独立 `assemble` / 发布内容完整的 fat AAR。

**架构：** 分两阶段落地。阶段一取消 javac 目录注入，改用归档级 `mergeEmbeddedClasses` 与单遍 `rewriteRClasses`，把 `copyToJavac`(162.6s)、`writeIndex`(31.0s)、`transformR`(210.4s) 的成本项直接消掉；阶段二在消费根遍历全图，逐节点消费**薄产物**并合并各节点**自有 class**，使 `assemble` 根模块不再触发任何中间模块的合并/重打包任务。非类内容（res/assets/jni/Manifest/ProGuard/libs/SPI/Kotlin）继续沿直接子模块分层继承，其薄产物已含整棵子树。

**技术栈：** Gradle Groovy 插件、Android Gradle Plugin 4.2 API、Gradle `LibraryVariant`、`java.util.zip`、Javassist、现有示例复合构建（AGP 7.0.2）、Java/Kotlin、DataBinding。

**依据：** `docs/superpowers/specs/2026-08-26-nested-embed-design.md`（2026-09-18 修订）。

---

## 文件结构

| 文件 | 职责 |
| --- | --- |
| `source/src/main/groovy/com/kezong/fataar/tasks/MergeEmbeddedClassesTask.groovy` | 新增。以流式方式把本模块薄产物 `classes.jar` 与全图各节点 `classes.jar` 合并为 `merged-classes.jar`，对重复 class 快速失败。 |
| `source/src/main/groovy/com/kezong/fataar/tasks/RewriteRClassesTask.groovy` | 新增。对合并后的 `classes.jar` 做单遍 R 引用改名，带常量池预筛，跳过不引用 R 的 class。 |
| `source/src/main/groovy/com/kezong/fataar/FlattenedEmbedNode.groovy` | 新增。扁平节点模型：后代 project、选中变体、薄产物 `bundleTask` 与文件、是否直接子模块。 |
| `source/src/main/groovy/com/kezong/fataar/NestedEmbedGraph.groovy` | 新增。图校验结果载体，过渡期同时携带旧 `NestedEmbedNode` 与新 `FlattenedEmbedNode`，任务 6 后只保留后者。 |
| `source/src/main/groovy/com/kezong/fataar/NestedEmbedGraphValidator.groovy` | 修改。在既有循环/菱形检测之上产出扁平节点列表，并检测同一项目被不同路径选中不同变体。 |
| `source/src/main/groovy/com/kezong/fataar/AndroidArchiveLibrary.java` | 修改。支持非 `ResolvedArtifact` 来源（`moduleKey` + `aarFile`），并标记 `direct`。 |
| `source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy` | 修改。删除 javac 注入与逐类索引；接入归档级类合并与 R 改写；消费范围改为扁平节点 + `direct` 过滤。 |
| `source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy` | 修改。移除 AGP `Transform` 注册，保存并下发扁平节点列表。 |
| `source/src/main/java/com/kezong/fataar/RClassesTransform.java` | 删除。AGP `Transform` 版 R 改写由 `RewriteRClassesTask` 取代。 |
| `source/src/main/groovy/com/kezong/fataar/NestedEmbedNode.groovy` | 删除（任务 6）。嵌套消费不再依赖子模块 `reBundleAar`。 |
| `source/src/main/groovy/com/kezong/fataar/DirectoryManager.groovy` | 修改。新增 `getMergedClassesDirectory`。 |
| `source/src/main/groovy/com/kezong/fataar/FatAarDiagnostics.groovy` | 修改。识别 `mergeEmbeddedClasses`、`rewriteRClasses` 两个新类别。 |
| `example/lib-main/build.gradle` | 修改。新增 `verifyNestedTaskRange`，断言根模块构建不触发后代合并/重打包任务。 |
| `README.md`、`README_CN.md` | 修改。补充嵌套消费不依赖中间最终产物、耗时不再随深度放大的说明。 |

## 关键不变式（实现时必须保持）

1. `bundle<Variant>Aar`（薄产物）**只含本模块自身 class**，但含**整棵子树非类内容**。
2. `reBundleAar<Variant>`（最终产物）的 `classes.jar` = 全图各节点自有 class 合并 + 单遍 R 改写。
3. 非类内容的合并范围是 `direct = true` 的节点（直接子模块）；class 与 DataBinding 的合并范围是全部扁平节点。
4. `bundle<Variant>Aar` 不得依赖任何类合并/重打包任务。

---

## 阶段一：归档级类合并（替代 javac 注入）

### 任务 1：以 `MergeEmbeddedClassesTask` 取代 `mergeClasses` 的 javac 注入

**文件：**
- 创建：`source/src/main/groovy/com/kezong/fataar/tasks/MergeEmbeddedClassesTask.groovy`
- 修改：`source/src/main/groovy/com/kezong/fataar/DirectoryManager.groovy`
- 修改：`source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy:618-723`（删除 `handleClassesMergeTask`）、`:748-788`（`processClassesAndJars`）、`:229-233`（删除 `mergedClassesIndexFile`）

- [ ] **步骤 1：新增 `DirectoryManager.getMergedClassesDirectory`**

在 `DirectoryManager.groovy` 的 `getMergeClassDirectory` 之后插入：

```groovy
    static File getMergedClassesDirectory(Project project, LibraryVariant variant) {
        return project.file("${project.buildDir}/intermediates/${INTERMEDIATES_TEMP_FOLDER}/merged-classes/${variant.name}")
    }
```

- [ ] **步骤 2：新增归档级合并任务**

创建 `MergeEmbeddedClassesTask.groovy`。它以流式方式合并，不落任何中间目录；对重复 class 快速失败，把图校验遗漏的重复变成明确的构建错误：

```groovy
package com.kezong.fataar.tasks

import com.kezong.fataar.FatUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 归档级合并：把「本模块薄产物 classes.jar（保留 META-INF/）」与「全图各节点 classes.jar（排除 META-INF/）」
 * 合为 merged-classes.jar。不物化目录，不做逐类哈希。
 */
class MergeEmbeddedClassesTask extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    File ownClassesJar

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    Set<File> embeddedClassesJars = Collections.emptySet()

    /** minify 打开时，本地 jar 的 class 也需并入；否则保持只走 libs/ 通道。 */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    Set<File> extraClassesJars = Collections.emptySet()

    @OutputFile
    File outputJar

    @TaskAction
    void merge() {
        if (!ownClassesJar.isFile()) {
            throw new GradleException("Own classes.jar is missing: '${ownClassesJar.absolutePath}'. " +
                    "The thin bundle AAR must be unpacked before merging embedded classes.")
        }
        Map<String, String> owners = new LinkedHashMap<>()
        outputJar.parentFile.mkdirs()
        ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputJar)))
        long copiedEntries = 0L
        try {
            copiedEntries += copyJar(ownClassesJar, output, owners, false, false)
            Set<File> embedded = (embeddedClassesJars ?: Collections.emptySet()) as LinkedHashSet<File>
            embedded.each { File jar ->
                int before = owners.size()
                copiedEntries += copyJar(jar, output, owners, true, true)
                if (owners.size() == before) {
                    throw new GradleException("Embedded archive contributed no class: '${jar.absolutePath}'. " +
                            "An empty contribution means the nested graph lost a descendant; check that the " +
                            "flattened node list covers every reachable Android project.")
                }
            }
            (extraClassesJars ?: Collections.emptySet()).each { File jar ->
                copiedEntries += copyJar(jar, output, owners, true, true)
            }
        } finally {
            output.close()
        }
        if (owners.isEmpty()) {
            throw new GradleException("Merged classes.jar is empty for '${ownClassesJar.absolutePath}' and " +
                    "${(embeddedClassesJars ?: Collections.emptySet()).size()} embedded archive(s).")
        }
        FatUtils.logAnytime("[fat-aar][classes] merged ${owners.size()} class(es), " +
                "${copiedEntries} entr(ies) -> ${outputJar.absolutePath}")
    }

    private long copyJar(File jar,
                         ZipOutputStream output,
                         Map<String, String> owners,
                         boolean excludeMetaInf,
                         boolean strictDuplicates) {
        if (!jar.isFile()) {
            throw new GradleException("Embedded classes.jar is missing: '${jar.absolutePath}'.")
        }
        long written = 0L
        ZipFile zip = new ZipFile(jar)
        try {
            Enumeration entries = zip.entries()
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement() as ZipEntry
                if (entry.directory) {
                    continue
                }
                if (excludeMetaInf && entry.name.startsWith('META-INF/')) {
                    continue
                }
                if (entry.name == 'META-INF/MANIFEST.MF') {
                    continue
                }
                if (entry.name.endsWith('.class')) {
                    String previous = owners.get(entry.name)
                    if (previous != null) {
                        if (strictDuplicates && previous != jar.absolutePath) {
                            throw new GradleException("Duplicate class '${entry.name}' while merging embedded " +
                                    "classes: '${previous}' and '${jar.absolutePath}'. The nested embed graph must " +
                                    "contain each descendant exactly once; keep one embed path and use compileOnly " +
                                    "for the other parents.")
                        }
                        continue
                    }
                    owners.put(entry.name, jar.absolutePath)
                }
                ZipEntry outEntry = new ZipEntry(entry.name)
                if (entry.time > 0L) {
                    outEntry.time = entry.time
                }
                output.putNextEntry(outEntry)
                InputStream input = zip.getInputStream(entry)
                try {
                    output << input
                } finally {
                    input.close()
                }
                output.closeEntry()
                written++
            }
        } finally {
            zip.close()
        }
        return written
    }
}
```

- [ ] **步骤 3：删除 `handleClassesMergeTask` 与注入索引**

在 `VariantProcessor.groovy`：

1. 删除方法 `mergedClassesIndexFile()`（`:229-233`）与字段 `private File mMergedClassesIndex`（`:45`）。
2. 删除整个 `handleClassesMergeTask(boolean)`（`:618-723`）。
3. 新增 import：`import com.kezong.fataar.tasks.MergeEmbeddedClassesTask`（与既有 `com.kezong.fataar.tasks.MergeDataBindingMetadataTask` 并列）。
4. 删除 import `java.nio.file.Files`、`java.nio.file.Path`、`java.nio.file.Paths`（仅清理流程使用）。保留 `org.gradle.api.internal.tasks.CachingTaskDependencyResolveContext`（`getTaskDependencies` 仍用）。

- [ ] **步骤 4：改写 `processClassesAndJars`**

把 `:748-788` 的 `processClassesAndJars` 整体替换为（阶段一仍消费直接子模块的最终产物，扁平化在任务 6 接入）：

```groovy
    private void processClassesAndJars(TaskProvider<Task> bundleTask) {
        boolean isMinifyEnabled = mVariant.getBuildType().isMinifyEnabled()
        TaskProvider syncLibTask = mProject.tasks.named(mVersionAdapter.getSyncLibJarsTaskPath())
        File reBundleDir = DirectoryManager.getReBundleDirectory(mProject, mVariant)
        File ownClassesJar = new File(reBundleDir, "classes.jar")
        File mergedClassesDir = DirectoryManager.getMergedClassesDirectory(mProject, mVariant)

        mFinalClassesJar = mProject.tasks.register("mergeEmbeddedClasses${mVariant.name.capitalize()}",
                MergeEmbeddedClassesTask) {
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'mergeEmbeddedClasses')
            it.dependsOn(mUnpackBundleTask)
            it.dependsOn(mExplodeTasks)
            it.ownClassesJar = ownClassesJar
            it.embeddedClassesJars = mAndroidArchiveLibraries.collect { it.classesJarFile } as Set<File>
            if (isMinifyEnabled) {
                Set<File> extra = new LinkedHashSet<>()
                mAndroidArchiveLibraries.each { extra.addAll(it.localJars) }
                extra.addAll(mJarFiles)
                it.extraClassesJars = extra
            }
            it.outputJar = new File(mergedClassesDir, "merged-classes.jar")
        }

        syncLibTask.configure {
            inputs.files(mAndroidArchiveLibraries.stream().map { it.libsFolder }.collect())
                    .withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(mJarFiles).withPathSensitivity(PathSensitivity.RELATIVE)
        }

        if (!isMinifyEnabled) {
            TaskProvider mergeJars = handleJarMergeTask(syncLibTask)
            bundleTask.configure {
                dependsOn(mergeJars)
            }
        }
    }
```

新增字段（与 `mMergeClassTask` 同区）：

```groovy
    private TaskProvider mFinalClassesJar
```

删除字段 `private TaskProvider mMergeClassTask`。

- [ ] **步骤 5：同步 `FatAarDiagnostics` 的任务类别识别**

在 `FatAarDiagnostics.groovy` 的 `isFallbackTask` 中，`name.startsWith('mergeclasses')` 之后追加：

```groovy
                    name.startsWith('mergeembeddedclasses') ||
```

在 `inferMetadata` 中，`mergeclasses` 分支之前插入：

```groovy
            if (name.startsWith('mergeembeddedclasses')) {
                category = 'mergeEmbeddedClasses'
            } else
```

- [ ] **步骤 6：构建插件并确认示例仍产出完整内容**

此时 `reBundleAar` 仍从 `reBundleDir` 打包，`classes.jar` 仍是 AGP 的薄产物，因此内容会暂时缺少被合并的类。本步骤只验证编译与任务注册：

```bash
./gradlew :source:build
cd example && ./gradlew :lib-main:tasks --all | grep mergeEmbeddedClasses
```

预期：插件编译通过；输出包含 `mergeEmbeddedClassesFlavor1Debug`、`mergeEmbeddedClassesFlavor2Debug`。

- [ ] **步骤 7：Commit**

```bash
git add source/src/main/groovy/com/kezong/fataar/DirectoryManager.groovy \
  source/src/main/groovy/com/kezong/fataar/FatAarDiagnostics.groovy \
  source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy \
  source/src/main/groovy/com/kezong/fataar/tasks/MergeEmbeddedClassesTask.groovy
git commit -m "perf(classes): merge embedded classes at archive level"
```

### 任务 2：`RewriteRClassesTask` 单遍改写，移除 AGP `Transform` 打包依赖

**文件：**
- 创建：`source/src/main/groovy/com/kezong/fataar/tasks/RewriteRClassesTask.groovy`
- 修改：`source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy:235-247`（`processRClasses`）、`:249-262`（删除 `transformRClasses`）、`:270-292`（删除 `collectRepackagedPackages`）、任务 1 已改写的 `processClassesAndJars`
- 修改：`source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy:34-52`
- 删除：`source/src/main/java/com/kezong/fataar/RClassesTransform.java`

- [ ] **步骤 1：新增改写任务**

创建 `RewriteRClassesTask.groovy`。关键点是**常量池预筛**：先在对原始字节里查找 `/R$` 标记，未命中直接原样写出，命中才交给 Javassist：

```groovy
package com.kezong.fataar.tasks

import com.kezong.fataar.FatUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import javassist.bytecode.ClassFile
import javassist.bytecode.ConstPool

import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 把「会被打进本次 fat aar 的全部包」的 R 引用改写到本模块 applicationId。
 * 每个 class 先做 /R$ 字节预筛，绝大多数 class 不引用 R，可直接原样写出。
 */
class RewriteRClassesTask extends DefaultTask {

    private static final byte[] R_MARKER = "/R\$".getBytes(StandardCharsets.UTF_8)

    private static final List<String> RESOURCE_TYPES = [
            "anim", "animator", "array", "attr", "bool", "color", "dimen",
            "drawable", "font", "fraction", "id", "integer", "interpolator", "layout", "menu", "mipmap",
            "navigation", "plurals", "raw", "string", "style", "styleable", "transition", "xml",
    ].asImmutable()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    File mergedClassesJar

    @Input
    String targetPackage

    /**
     * 兜底：某些产物只在 R.txt 里出现符号。包名从 AAR Manifest 读取，必须在 explode 之后才能取，
     * 因此用 Provider 惰性求值，避免配置期读取尚不存在的 Manifest。
     */
    @Input
    Provider<Set<String>> extraRepackagedPackages = null

    @OutputFile
    File outputJar

    @TaskAction
    void rewrite() {
        if (!mergedClassesJar.isFile()) {
            throw new GradleException("Merged classes.jar is missing: '${mergedClassesJar.absolutePath}'.")
        }
        Set<String> packages = new LinkedHashSet<>()
        if (extraRepackagedPackages != null && extraRepackagedPackages.present) {
            packages.addAll(extraRepackagedPackages.get())
        }
        ZipFile scanner = new ZipFile(mergedClassesJar)
        try {
            Enumeration entries = scanner.entries()
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement() as ZipEntry
                if (!entry.directory && entry.name.endsWith('.class')) {
                    int slash = entry.name.lastIndexOf('/')
                    if (slash > 0) {
                        packages.add(entry.name.substring(0, slash).replace('/', '.'))
                    }
                }
            }
        } finally {
            scanner.close()
        }

        Map<String, String> table = buildTransformTable(packages)
        outputJar.parentFile.mkdirs()
        ZipFile input = new ZipFile(mergedClassesJar)
        ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputJar)))
        long rewritten = 0L
        long copied = 0L
        try {
            Enumeration entries = input.entries()
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement() as ZipEntry
                if (entry.directory) {
                    continue
                }
                byte[] bytes = input.getInputStream(entry).bytes
                byte[] result = bytes
                if (entry.name.endsWith('.class') && indexOf(bytes, R_MARKER) >= 0) {
                    result = rename(entry.name, bytes, table)
                    rewritten++
                } else {
                    copied++
                }
                ZipEntry outEntry = new ZipEntry(entry.name)
                if (entry.time > 0L) {
                    outEntry.time = entry.time
                }
                output.putNextEntry(outEntry)
                output.write(result)
                output.closeEntry()
            }
        } finally {
            output.close()
            input.close()
        }
        FatUtils.logAnytime("[fat-aar][R] '${targetPackage}': rewritten ${rewritten} class(es), " +
                "passthrough ${copied} entr(ies) over ${packages.size()} package(s)")
    }

    private Map<String, String> buildTransformTable(Collection<String> packages) {
        String target = targetPackage.replace('.', '/')
        Map<String, String> table = new LinkedHashMap<>()
        RESOURCE_TYPES.each { String resource ->
            String targetClass = target + '/R$' + resource
            packages.each { String pkg ->
                if (pkg != null && !pkg.isEmpty()) {
                    table.put(pkg.replace('.', '/') + '/R$' + resource, targetClass)
                }
            }
        }
        return table
    }

    private static byte[] rename(String entryName, byte[] bytes, Map<String, String> table) {
        ClassFile classFile = new ClassFile(new DataInputStream(new ByteArrayInputStream(bytes)))
        ConstPool constPool = classFile.getConstPool()
        constPool.renameClass(table)
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length)
        DataOutputStream data = new DataOutputStream(buffer)
        classFile.write(data)
        data.flush()
        return buffer.toByteArray()
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return -1
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer
                }
            }
            return i
        }
        return -1
    }
}
```

- [ ] **步骤 2：在 `processClassesAndJars` 接入改写任务**

先补 import：`import com.kezong.fataar.tasks.RewriteRClassesTask`。

在任务 1 写好的 `mFinalClassesJar = ...register("mergeEmbeddedClasses...")` 之后、`syncLibTask.configure` 之前插入：

```groovy
        if (mProject.fataar.transformR) {
            TaskProvider mergeTask = mFinalClassesJar
            mFinalClassesJar = mProject.tasks.register("rewriteRClasses${mVariant.name.capitalize()}",
                    RewriteRClassesTask) {
                FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'rewriteRClasses')
                it.dependsOn(mergeTask)
                it.mergedClassesJar = new File(mergedClassesDir, "merged-classes.jar")
                it.targetPackage = mVariant.getApplicationId()
                it.extraRepackagedPackages = mProject.provider {
                    mAndroidArchiveLibraries.collect { it.packageName } as Set<String>
                }
                it.outputJar = new File(mergedClassesDir, "rewritten-classes.jar")
            }
        }
```

（`transformR = false` 分支无需在此额外处理：`processRClasses` 会注册别名 R 任务，其产物经 `rebundle/libs` 进入最终 AAR。）

- [ ] **步骤 3：重写 `processRClasses`**

把 `:235-247` 替换为（`configureReBundleAarTask` 仍在方法内创建，任务 3 才会把它前移）：

```groovy
    private void processRClasses(TaskProvider<Task> bundleTask) {
        TaskProvider reBundleTask = configureReBundleAarTask(bundleTask)
        if (mProject.fataar.transformR) {
            // R 改写已由 rewriteRClasses 在类合并阶段完成，无需 AGP Transform。
            return
        }
        generateRClasses(bundleTask, reBundleTask)
    }
```

同时删除 `:249-292` 的 `transformRClasses` 与 `collectRepackagedPackages` 两个方法。

- [ ] **步骤 4：删除 AGP `Transform` 注册**

在 `FatAarPlugin.groovy`：

1. 删除 `registerTransform()` 方法（`:48-52`）与 `apply` 中的 `registerTransform()` 调用。
2. 删除字段 `private RClassesTransform transform`（`:28`）。
3. 删除 import `org.gradle.api.ProjectConfigurationException`（`:7`，未使用）。
4. 把 `processVariant` 调用从 `processor.processVariant(artifacts, firstLevelDependencies, transform)` 改为 `processor.processVariant(artifacts, firstLevelDependencies)`。
5. 同步修改 `VariantProcessor.processVariant` 签名，删除 `RClassesTransform transform` 形参，并把 `processRClasses(transform, bundleTask)` 改为 `processRClasses(bundleTask)`。

删除 `source/src/main/java/com/kezong/fataar/RClassesTransform.java`。

- [ ] **步骤 5：同步 `FatAarDiagnostics`**

`isFallbackTask` 增加 `name.contains('rewriterclasses')`；`inferMetadata` 增加分支：

```groovy
            } else if (name.contains('rewriterclasses')) {
                category = 'rewriteRClasses'
            } else if (name.contains('transformr')) {
                category = 'transformR'
            } else
```

（保留 `transformr` 分支，兼容尚未升级的模块诊断。）

- [ ] **步骤 6：构建插件并确认无 `transformR` 任务依赖**

```bash
./gradlew :source:build
cd example && ./gradlew :lib-main:assembleFlavor1Debug --dry-run | grep -i transformr
```

预期：插件编译通过；第二条命令无输出（`transformClassesWithTransformRFor...` 不再进入任务图）。

- [ ] **步骤 7：Commit**

```bash
git add source/src/main/groovy/com/kezong/fataar source/src/main/java/com/kezong/fataar
git rm source/src/main/java/com/kezong/fataar/RClassesTransform.java
git commit -m "perf(r): rewrite R references in a single archive-level pass"
```

### 任务 3：`reBundleAar` 用合并改写后的 `classes.jar` 组装最终产物

**文件：**
- 修改：`source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy:162-219`（`configureReBundleAarTask`）、`:78-107`（`processVariant`）

- [ ] **步骤 1：把 `reBundleAar` 的创建提前并保存句柄**

在 `processVariant` 中，把 `configureReBundleAarTask` 的调用前移，并保存返回值：

```groovy
    void processVariant(Collection<ResolvedArtifact> artifacts,
                        Collection<ResolvedDependency> dependencies) {
        String taskPath = 'pre' + mVariant.name.capitalize() + 'Build'
        TaskProvider prepareTask = mProject.tasks.named(taskPath)
        if (prepareTask == null) {
            throw new RuntimeException("Can not find task ${taskPath}!")
        }
        TaskProvider bundleTask = VersionAdapter.getBundleTaskProvider(mProject, mVariant.name)
        mReBundleTask = configureReBundleAarTask(bundleTask)
        preEmbed(artifacts, dependencies, prepareTask)
        processArtifacts(artifacts, prepareTask, bundleTask)
        processClassesAndJars(bundleTask)
        if (mAndroidArchiveLibraries.isEmpty()) {
            return
        }
        processManifest()
        processResources()
        processAssets()
        processJniLibs()
        processConsumerProguard()
        processGenerateProguard()

        processJavaResourcesHooks()

        processRClasses(bundleTask)

        processDataBinding()
    }
```

新增字段：

```groovy
    private TaskProvider mReBundleTask
```

同时把任务 2 改写好的 `processRClasses` 调整为复用该句柄，不再重复创建：

```groovy
    private void processRClasses(TaskProvider<Task> bundleTask) {
        if (mProject.fataar.transformR) {
            // R 改写已由 rewriteRClasses 在类合并阶段完成，无需 AGP Transform。
            return
        }
        generateRClasses(bundleTask, mReBundleTask)
    }
```

- [ ] **步骤 2：在 `reBundleAar` 中替换 `classes.jar`**

在 `configureReBundleAarTask` 的 `Zip` 配置里，把 `it.from reBundleDir` / `it.include "**"` 替换为「薄产物目录排除 `classes.jar`」+「合并改写后的 jar 重命名为 `classes.jar`」：

```groovy
            it.from(reBundleDir) {
                it.include "**"
                // classes.jar 由归档级合并 + 单遍 R 改写产出，不再使用 AGP 薄产物里的版本
                it.exclude "classes.jar"
            }
            // 必须放在独立 spec 里：exclude 会被子 spec 继承，写在任务级会把重命名后的 classes.jar 一并排除
            it.from({ mFinalClassesJar == null ? [] : [mFinalClassesJar] }) { spec ->
                spec.rename { 'classes.jar' }
            }
```

注意：`mFinalClassesJar` 在 `configureReBundleAarTask` 执行时尚未赋值（它由随后的 `processClassesAndJars` 注册），因此这里必须用闭包惰性求值，也不要在此处写 `inputs`。

`MergeEmbeddedClassesTask` 与 `RewriteRClassesTask` 都必须暴露 `outputJar` 为 `@OutputFile`（任务 1、2 已实现）。

- [ ] **步骤 3：让 `reBundleAar` 依赖最终 jar 任务**

在 `processClassesAndJars` 末尾（`syncLibTask.configure { ... }` 之后）追加：

```groovy
        mReBundleTask.configure {
            dependsOn(mFinalClassesJar)
            inputs.file(mFinalClassesJar.flatMap { Task t -> t.outputJar })
                    .withPathSensitivity(PathSensitivity.RELATIVE)
        }
```

（这段必须写在 `processClassesAndJars` 末尾，此时 `mFinalClassesJar` 已赋值，`flatMap` 才能取到 `outputJar`；不要写到 `configureReBundleAarTask` 里。）

- [ ] **步骤 4：运行完整内容回归**

```bash
cd example && ./gradlew \
  :lib-aar:verifyNestedFatAarFlavor1Debug \
  :lib-aar:verifyNestedFatAarFlavor2Debug \
  :lib-main:verifyNestedFatAarFlavor1Debug \
  :lib-main:verifyNestedFatAarFlavor2Debug \
  :app:assembleDebug --stacktrace
```

预期：全部通过。此时架构仍是「每层消费子模块最终产物」，但 javac 注入、逐类 SHA-1、AGP Transform 三段开销已消失；`lib-aar`、`lib-main` 的最终聚合类、资源、Manifest、JNI、ProGuard、DataBinding、SPI/Kotlin 断言全部成立。

- [ ] **步骤 5：Commit**

```bash
git add source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy
git commit -m "perf(aar): assemble final classes.jar from merged rewritten classes"
```

---

## 阶段二：消费端扁平聚合

### 任务 4：扁平节点模型与图校验器产出

**文件：**
- 创建：`source/src/main/groovy/com/kezong/fataar/FlattenedEmbedNode.groovy`
- 创建：`source/src/main/groovy/com/kezong/fataar/NestedEmbedGraph.groovy`
- 修改：`source/src/main/groovy/com/kezong/fataar/NestedEmbedGraphValidator.groovy`
- 修改：`source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy:32,98-105,163-168`

- [ ] **步骤 1：定义扁平节点模型**

创建 `FlattenedEmbedNode.groovy`：

```groovy
package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * 消费根视角下的一个扁平嵌入节点：后代项目 + 选中变体 + 自有薄产物。
 * direct 为 true 表示它是消费根的直接子模块，其薄产物承担子树非类内容的继承。
 */
class FlattenedEmbedNode {

    final Project project

    final LibraryVariant requestedVariant

    final SelectedVariantArtifact selection

    final boolean direct

    FlattenedEmbedNode(Project project,
                       LibraryVariant requestedVariant,
                       SelectedVariantArtifact selection,
                       boolean direct) {
        this.project = project
        this.requestedVariant = requestedVariant
        this.selection = selection
        this.direct = direct
    }

    String getKey() {
        return "${project.path}@${selection.variant.name}"
    }

    File getAarFile() {
        return selection.outputFile
    }

    TaskProvider getBundleTask() {
        return selection.bundleTask
    }
}
```

- [ ] **步骤 2：定义图校验结果载体**

创建 `NestedEmbedGraph.groovy`（过渡期同时携带两套结果，任务 6 删除嵌套部分后只保留 `flattenedNodes`）：

```groovy
package com.kezong.fataar

class NestedEmbedGraph {

    final Collection<NestedEmbedNode> nestedNodes

    final Collection<FlattenedEmbedNode> flattenedNodes

    NestedEmbedGraph(Collection<NestedEmbedNode> nestedNodes,
                     Collection<FlattenedEmbedNode> flattenedNodes) {
        this.nestedNodes = nestedNodes == null ? Collections.emptyList() : nestedNodes
        this.flattenedNodes = flattenedNodes == null ? Collections.emptyList() : flattenedNodes
    }
}
```

- [ ] **步骤 3：校验器同时产出扁平列表并检测变体冲突**

在 `NestedEmbedGraphValidator.groovy` 中：

1. 新增字段：

```groovy
    private final Collection<FlattenedEmbedNode> flattenedNodes = new LinkedHashSet<>()
    private final Map<String, String> selectedVariantByProject = new LinkedHashMap<>()
    private final Map<String, List<String>> pathByProject = new LinkedHashMap<>()
```

2. `validate()` 改为返回载体：

```groovy
    NestedEmbedGraph validate() {
        String rootKey = variantKey(rootProject, rootVariant)
        walk(rootProject, rootVariant, [rootKey])
        return new NestedEmbedGraph(nodes, flattenedNodes)
    }
```

3. 用下面的实现整体替换现有 `walk(Project, LibraryVariant, List)` 方法体（原方法的嵌套节点登记与叶子分支都并入这里，避免重复登记）：

```groovy
    private void walk(Project parent, LibraryVariant requestedVariant, List<String> activePath) {
        String parentKey = variantKey(parent, requestedVariant)
        FatAarPlugin.getApplicableEmbedConfigurations(parent, requestedVariant).each { configuration ->
            configuration.dependencies.findAll { it instanceof ProjectDependency }.each { ProjectDependency dependency ->
                Project child = dependency.dependencyProject
                if (!child.plugins.hasPlugin('com.android.library')) {
                    return
                }

                SelectedVariantArtifact selection = FlavorArtifact.selectVariantArtifact(child, requestedVariant)
                if (selection == null) {
                    throw configurationException("Cannot select an Android library variant for nested embed: " +
                            "parent '${parent.path}', child '${child.path}', requested variant '${requestedVariant.name}'.")
                }

                String childKey = variantKey(child, selection.variant)
                String edgeKey = "${parentKey}->${childKey}"
                if (!visitedEdges.add(edgeKey)) {
                    return
                }
                if (activePath.contains(childKey)) {
                    List<String> cycle = new ArrayList<>(activePath)
                    cycle.add(childKey)
                    throw configurationException("Nested embed project dependency cycle detected: ${cycle.join(' -> ')}")
                }

                Collection<Configuration> childConfigurations =
                        FatAarPlugin.getNonEmptyApplicableEmbedConfigurations(child, selection.variant)
                if (!childConfigurations.isEmpty() && !child.plugins.hasPlugin('com.kezong.fat-aar')) {
                    String names = childConfigurations.collect { it.name }.join(', ')
                    throw configurationException("Invalid nested embed configuration: parent '${parent.path}', " +
                            "child '${child.path}', requested variant '${requestedVariant.name}', " +
                            "selected variant '${selection.variant.name}', configurations '${names}'. " +
                            "应用 com.kezong.fat-aar 或改用 implementation/api。")
                }

                List<String> childPath = new ArrayList<>(activePath)
                childPath.add(childKey)
                String previousVariant = selectedVariantByProject.get(child.path)
                if (previousVariant != null && previousVariant != selection.variant.name) {
                    throw configurationException("Nested embed selects conflicting variants for project " +
                            "'${child.path}': '${pathByProject.get(child.path).join(' -> ')}' selects " +
                            "'${previousVariant}', '${childPath.join(' -> ')}' selects '${selection.variant.name}'. " +
                            "A flattened fat AAR can contain only one variant of each descendant.")
                }

                registerPath(parentKey, childKey, activePath)
                flattenedNodes.add(new FlattenedEmbedNode(child, requestedVariant, selection, parent == rootProject))
                selectedVariantByProject.put(child.path, selection.variant.name)
                pathByProject.put(child.path, childPath)

                if (childConfigurations.isEmpty()) {
                    // 叶子模块自身没有 embed：仍需登记为扁平节点，消费根要收集它的自有 class。
                    return
                }
                TaskProvider reBundleTask = findReBundleTask(child, selection.variant)
                if (reBundleTask == null) {
                    String names = childConfigurations.collect { it.name }.join(', ')
                    String expectedTask = "reBundleAar${selection.variant.name.capitalize()}"
                    File expectedFinal = finalAarFile(child, selection.variant, selection.outputFile)
                    throw configurationException("Nested fat AAR cannot produce a final AAR: parent '${parent.path}', " +
                            "child '${child.path}', requested variant '${requestedVariant.name}', " +
                            "selected variant '${selection.variant.name}', configurations '${names}', " +
                            "expected task '${expectedTask}', source AAR '${selection.outputFile.absolutePath}', " +
                            "expected final AAR '${expectedFinal.absolutePath}'. Ensure the child module's nested AAR build " +
                            "produces a final AAR; do not downgrade it to a thin AAR.")
                }
                nodes.add(new NestedEmbedNode(parent, child, requestedVariant.name, selection, reBundleTask,
                        finalAarFile(child, selection.variant, selection.outputFile)))
                if (completed.add(childKey)) {
                    walk(child, selection.variant, childPath)
                }
            }
        }
    }
```

（`nodes` 只登记「已应用插件且自身有 embed」的嵌套节点，保持阶段一 `VariantProcessor` 的既有消费语义；叶子模块只进 `flattenedNodes`。任务 6 会连同 `nodes` 与 `NestedEmbedNode` 一起删除。）

- [ ] **步骤 4：插件保存并下发扁平列表**

在 `FatAarPlugin.groovy`：

1. 新增字段：

```groovy
    final Map<String, Collection<FlattenedEmbedNode>> flattenedEmbedNodesByVariant = new LinkedHashMap<>()
```

2. `validateNestedEmbedGraphs` 改为：

```groovy
    private void validateNestedEmbedGraphs() {
        project.android.libraryVariants.all { variant ->
            NestedEmbedGraph graph = new NestedEmbedGraphValidator(project, variant as LibraryVariant).validate()
            nestedEmbedNodesByVariant.put(variant.name, graph.nestedNodes)
            flattenedEmbedNodesByVariant.put(variant.name, graph.flattenedNodes)
        }
    }
```

- [ ] **步骤 5：验证扁平列表覆盖全图**

在 `example` 下运行并核对节点数（`:lib-aar` 是直接子模块，`:lib-aar2` 是深层节点）：

```bash
cd example && ./gradlew :lib-main:assembleFlavor1Debug --dry-run --info 2>&1 | grep -c "explode"
```

预期：任务图里仍只有阶段一的解包任务；本步骤只确认配置期不再抛错：

```bash
cd example && ./gradlew :lib-main:help --stacktrace
```

- [ ] **步骤 6：Commit**

```bash
git add source/src/main/groovy/com/kezong/fataar/FlattenedEmbedNode.groovy \
  source/src/main/groovy/com/kezong/fataar/NestedEmbedGraph.groovy \
  source/src/main/groovy/com/kezong/fataar/NestedEmbedGraphValidator.groovy \
  source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy
git commit -m "feat(graph): flatten nested embed graph at the consuming root"
```

### 任务 5：`AndroidArchiveLibrary` 支持合成来源与 `direct` 标记

**文件：**
- 修改：`source/src/main/java/com/kezong/fataar/AndroidArchiveLibrary.java`

- [ ] **步骤 1：新增字段与构造器**

把字段区（`:18-31`）改为：

```java
    private final Project mProject;

    private final ResolvedArtifact mArtifact;

    private final String mModuleKey;

    private final String mName;

    private final String mVariantName;

    private File mAarFile;

    private boolean mDirect;

    private String mPackageName;

    /**
     * 当此 archive 来源于本地 Project 依赖（embed project(':xxx')）时，
     * 保存该子项目的真实引用，用于跨项目任务依赖声明。
     * 对于远程 Maven AAR 依赖，此字段为 null。
     */
    private Project mEmbedProject;

    public AndroidArchiveLibrary(Project project, ResolvedArtifact artifact, String variantName) {
        if (!"aar".equals(artifact.getType())) {
            throw new IllegalArgumentException("artifact must be aar type!");
        }
        mProject = project;
        mArtifact = artifact;
        ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
        mModuleKey = sanitize(id.getGroup() + "__" + id.getName() + "__" + id.getVersion());
        mName = id.getName();
        mVariantName = variantName;
        mAarFile = artifact.getFile();
    }

    public AndroidArchiveLibrary(Project project, String moduleKey, String name, String variantName, File aarFile) {
        mProject = project;
        mArtifact = null;
        mModuleKey = sanitize(moduleKey);
        mName = name;
        mVariantName = variantName;
        mAarFile = aarFile;
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
```

- [ ] **步骤 2：调整坐标访问器与解包根目录**

把 `getGroup()`、`getName()`、`getVersion()`、`getRootFolder()`（`:46-67`）替换为：

```java
    public String getModuleKey() {
        return mModuleKey;
    }

    public String getName() {
        return mName;
    }

    /** Gradle 任务名安全标识，用于 explode 任务命名。 */
    public String getTaskKey() {
        return mModuleKey.replaceAll("[^A-Za-z0-9]", "_");
    }

    public File getRootFolder() {
        File explodedRootDir = mProject.file(
                mProject.getBuildDir() + "/intermediates" + "/exploded-aar/");
        return mProject.file(explodedRootDir + "/" + mModuleKey + "/" + mVariantName);
    }
```

- [ ] **步骤 3：新增 `aarFile` 与 `direct` 访问器**

在 `getEmbedProject` 之前插入：

```java
    public File getAarFile() {
        return mAarFile;
    }

    public void setAarFile(File aarFile) {
        this.mAarFile = aarFile;
    }

    public boolean isDirect() {
        return mDirect;
    }

    public void setDirect(boolean direct) {
        this.mDirect = direct;
    }
```

- [ ] **步骤 4：修正既有调用点，让插件继续编译**

`VariantProcessor.groovy` 中 `explode${group}${name}${variant}` 的任务命名（`:537-540`）改为：

```groovy
                String taskName = "explode${library.getTaskKey()}${mVariant.name.capitalize()}"
```

并把原来的 `artifact.getModuleVersion().id.group.capitalize()` / `artifact.name.capitalize()` 局部变量删除。

运行：

```bash
./gradlew :source:build
```

预期：编译通过。

- [ ] **步骤 5：Commit**

```bash
git add source/src/main/java/com/kezong/fataar/AndroidArchiveLibrary.java \
  source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy
git commit -m "refactor(archive): allow synthetic embedded archive sources"
```

### 任务 6：`VariantProcessor` 切换到薄产物消费与全图类合并

**文件：**
- 修改：`source/src/main/groovy/com/kezong/fataar/VariantProcessor.groovy:30-107,147-160,412-580,585-616,725-743,796-868,873-937`
- 修改：`source/src/main/groovy/com/kezong/fataar/FatAarPlugin.groovy:98-105`
- 删除：`source/src/main/groovy/com/kezong/fataar/NestedEmbedNode.groovy`、`source/src/main/groovy/com/kezong/fataar/NestedEmbedGraph.groovy` 的 `nestedNodes`，以及 `NestedEmbedGraphValidator` 的 `nodes` 字段

- [ ] **步骤 1：替换构造函数与字段**

`VariantProcessor` 字段区：删除 `private Collection<NestedEmbedNode> mNestedEmbedNodes`，新增：

```groovy
    private Collection<FlattenedEmbedNode> mFlattenedEmbedNodes
```

构造函数改为：

```groovy
    VariantProcessor(Project project,
                     LibraryVariant variant,
                     Map<String, Project> embedProjectsMap,
                     Collection<FlattenedEmbedNode> flattenedEmbedNodes = Collections.emptyList(),
                     Map<String, SelectedVariantArtifact> syntheticArtifactSelections = Collections.emptyMap()) {
        mProject = project
        mVariant = variant
        mVersionAdapter = new VersionAdapter(project, variant)
        mEmbedProjectsMap = embedProjectsMap ?: Collections.emptyMap()
        mFlattenedEmbedNodes = flattenedEmbedNodes ?: Collections.emptyList()
        mSyntheticArtifactSelections = syntheticArtifactSelections ?: Collections.emptyMap()
    }
```

`FatAarPlugin.doAfterEvaluate` 中改为传入 `flattenedEmbedNodesByVariant.get(variant.name)`。

- [ ] **步骤 2：删除嵌套节点的定位与校验代码**

删除 `findNestedNode`、`selectNestedNode`、`verifyNestedAarOutput`（`:435-514`）。用下面这个只校验薄产物的方法替代：

```groovy
    private void verifyEmbeddedAar(File aar, AndroidArchiveLibrary library) {
        String details = "project '${mProject.path}', variant '${mVariant.name}', " +
                "embedded '${library.getName()}', archive '${aar.absolutePath}'"
        if (aar == null || !aar.isFile() || !aar.canRead()) {
            throw new GradleException("Embedded thin AAR is not readable: ${details}")
        }
        java.util.zip.ZipFile zip = null
        try {
            zip = new java.util.zip.ZipFile(aar)
            if (zip.getEntry('AndroidManifest.xml') == null) {
                throw new GradleException("Embedded thin AAR is invalid (missing AndroidManifest.xml): ${details}")
            }
        } catch (GradleException exception) {
            throw exception
        } catch (Exception exception) {
            throw new GradleException("Embedded thin AAR is not a readable AAR: ${details}", exception)
        } finally {
            if (zip != null) {
                zip.close()
            }
        }
    }
```

- [ ] **步骤 3：`processArtifacts` 改为直接消费解析到的薄产物**

把 `:520-580` 替换为：

```groovy
    private void processArtifacts(Collection<ResolvedArtifact> artifacts,
                                  TaskProvider<Task> prepareTask,
                                  TaskProvider<Task> bundleTask) {
        if (artifacts == null) {
            return
        }
        for (final ResolvedArtifact artifact in artifacts) {
            if (FatAarPlugin.ARTIFACT_TYPE_JAR == artifact.type) {
                addJarFile(artifact.file)
            } else if (FatAarPlugin.ARTIFACT_TYPE_AAR == artifact.type) {
                AndroidArchiveLibrary archiveLibrary = new AndroidArchiveLibrary(mProject, artifact, mVariant.name)
                archiveLibrary.setAarFile(artifact.file)
                archiveLibrary.setDirect(true)
                Project embedProj = findEmbeddedProject(artifact)
                if (embedProj != null && embedProj != mProject) {
                    archiveLibrary.setEmbedProject(embedProj)
                }
                addAndroidArchiveLibrary(archiveLibrary)
                createExplodeTask(archiveLibrary, artifact.file, getTaskDependencies(artifact),
                        prepareTask, true, bundleTask)
            }
        }
    }
```

- [ ] **步骤 4：新增扁平节点解包**

在 `processArtifacts` 之后新增：

```groovy
    /**
     * 把扁平列表里尚未由 processArtifacts 覆盖的深层节点解包出来，供 class 合并与 DataBinding 聚合使用。
     * 深层节点不参与 sourceSet / Manifest / JNI 等非类内容合并：直接子模块的薄产物已含整棵子树。
     */
    private void processFlattenedEmbedNodes(TaskProvider<Task> bundleTask) {
        mFlattenedEmbedNodes.each { FlattenedEmbedNode node ->
            if (isAlreadyCollected(node)) {
                return
            }
            AndroidArchiveLibrary archiveLibrary = new AndroidArchiveLibrary(mProject, node.key,
                    node.project.name, node.selection.variant.name, node.aarFile)
            archiveLibrary.setDirect(false)
            archiveLibrary.setEmbedProject(node.project)
            addAndroidArchiveLibrary(archiveLibrary)
            createExplodeTask(archiveLibrary, node.aarFile, [node.bundleTask.get()] as Set<Task>,
                    null, false, bundleTask)
        }
    }

    private boolean isAlreadyCollected(FlattenedEmbedNode node) {
        String target = node.aarFile.absoluteFile.toPath().normalize().toString()
        return mAndroidArchiveLibraries.any { AndroidArchiveLibrary library ->
            library.aarFile != null &&
                    library.aarFile.absoluteFile.toPath().normalize().toString() == target
        }
    }
```

- [ ] **步骤 5：抽出 `createExplodeTask`**

新增方法（取代 `processArtifacts` 内联的任务创建，`gateVariantPipeline` 为 true 时保持对 javac/bundle 的既有门控）：

```groovy
    private Task createExplodeTask(AndroidArchiveLibrary library,
                                   File inputAar,
                                   Collection<Task> dependencies,
                                   TaskProvider<Task> prepareTask,
                                   boolean gateVariantPipeline,
                                   TaskProvider<Task> bundleTask) {
        final File zipFolder = library.getRootFolder()
        zipFolder.mkdirs()
        String taskName = "explode${library.getTaskKey()}${mVariant.name.capitalize()}"
        Task explodeTask = mProject.tasks.create(taskName, Copy) {
            FatAarDiagnostics.markTask(mProject, it, mVariant.name, 'explode')
            doFirst {
                FatAarDiagnostics.recordArchive(mProject, mVariant.name, taskName, inputAar, !library.isDirect())
            }
            from mProject.zipTree(inputAar.absolutePath)
            into zipFolder
            inputs.file(inputAar).withPathSensitivity(PathSensitivity.RELATIVE)
            outputs.dir(zipFolder)
            doFirst {
                zipFolder.deleteDir()
                verifyEmbeddedAar(inputAar, library)
            }
        }
        if (dependencies != null && !dependencies.isEmpty()) {
            explodeTask.dependsOn(dependencies)
        } else if (prepareTask != null) {
            explodeTask.dependsOn(prepareTask)
        }
        if (gateVariantPipeline) {
            mVersionAdapter.getJavaCompileTask().dependsOn(explodeTask)
            bundleTask.configure {
                dependsOn(explodeTask)
            }
        }
        mExplodeTasks.add(explodeTask)
        return explodeTask
    }
```

- [ ] **步骤 6：`processVariant` 调用扁平节点解包**

在 `processVariant` 里 `processArtifacts(artifacts, prepareTask, bundleTask)` 之后插入：

```groovy
        processFlattenedEmbedNodes(bundleTask)
```

- [ ] **步骤 7：非类内容合并范围收敛到直接子模块**

在 `VariantProcessor` 中新增过滤集合属性：

```groovy
    private Collection<AndroidArchiveLibrary> getDirectLibraries() {
        return mAndroidArchiveLibraries.findAll { it.isDirect() }
    }
```

并替换以下方法的遍历源：

| 方法 | 原表达式 | 新表达式 |
| --- | --- | --- |
| `processManifest` (`:598`) | `for (archiveLibrary in mAndroidArchiveLibraries)` | `for (archiveLibrary in getDirectLibraries())` |
| `processResources` (`:808`) | `for (archiveLibrary in mAndroidArchiveLibraries)` | `for (archiveLibrary in getDirectLibraries())` |
| `processAssets` (`:832`) | 同上 | `for (archiveLibrary in getDirectLibraries())` |
| `processJniLibs` (`:857`) | 同上 | `for (archiveLibrary in getDirectLibraries())` |
| `processConsumerProguard` (`:884`) | `mAndroidArchiveLibraries.stream().map { it.proguardRules }.collect()` | `getDirectLibraries().stream().map { it.proguardRules }.collect()` |
| `processGenerateProguard` (`:920`) | 同上 | 同上 |
| `processJavaResourcesHooks` (`:348`) | `mAndroidArchiveLibraries.collect { it.rootFolder }` | `getDirectLibraries().collect { it.rootFolder }` |
| `handleJarMergeTask` (`:732`) | `mAndroidArchiveLibraries.stream().map { it.libsFolder }.collect()` | `getDirectLibraries().stream().map { it.libsFolder }.collect()` |

`processDataBinding`（`:307-309`）保持全量，把注释补上：

```groovy
        Collection<File> metadataRoots = new LinkedHashSet<>()
        metadataRoots.add(reBundleDir)
        // DataBinding 聚合只写入最终产物，薄产物只含自身 metadata，因此必须取全图节点
        metadataRoots.addAll(mAndroidArchiveLibraries.collect { it.rootFolder })
```

- [ ] **步骤 8：删除过渡期的嵌套图代码**

1. `FatAarPlugin`：删除字段 `nestedEmbedNodesByVariant`、`validateNestedEmbedGraphs` 中对 `graph.nestedNodes` 的赋值与 `NestedEmbedGraph` 的嵌套部分；`NestedEmbedGraphValidator` 删除 `nodes` 集合与 `nodes.add(...)` 调用，`validate()` 直接返回 `Collection<FlattenedEmbedNode>`；删除 `findReBundleTask`、`finalAarFile` 两个私有方法。
2. 删除 `NestedEmbedNode.groovy` 与 `NestedEmbedGraph.groovy`。
3. `FatAarPlugin.doAfterEvaluate` 中把 `NestedEmbedGraph graph = ...` 的调用改为：

```groovy
            Collection<FlattenedEmbedNode> flattened =
                    new NestedEmbedGraphValidator(project, variant as LibraryVariant).validate()
```

并把 `nestedEmbedNodesByVariant.get(variant.name)` 换成 `flattened`。

- [ ] **步骤 9：运行内容回归与独立产物回归**

```bash
cd example && ./gradlew :lib-aar:verifyNestedFatAarFlavor1Debug :lib-aar:verifyNestedFatAarFlavor2Debug --stacktrace
```

预期：`:lib-aar` 作为消费根独立构建时，其最终 AAR 仍包含 `:lib-aar2` 的全部标记（class、资源、Manifest、JNI、ProGuard、DataBinding、SPI/Kotlin）。

```bash
cd example && ./gradlew :lib-main:verifyNestedFatAarFlavor1Debug :lib-main:verifyNestedFatAarFlavor2Debug :app:assembleDebug --stacktrace
```

预期：全部通过，且 `:app` 能编译对 `Aar2LibClass` 与 `R.string.app_name_aar2` 的引用。

- [ ] **步骤 10：Commit**

```bash
git add -A source/src/main
git rm source/src/main/groovy/com/kezong/fataar/NestedEmbedNode.groovy \
  source/src/main/groovy/com/kezong/fataar/NestedEmbedGraph.groovy
git commit -m "perf(nested): flatten descendant classes at the consuming root"
```

### 任务 7：任务范围回归断言

**文件：**
- 修改：`example/lib-main/build.gradle:110-125`

- [ ] **步骤 1：在 `example/lib-main/build.gradle` 注册断言任务**

在 `gradle.projectsEvaluated { ... }` 块内 `registerNestedFatAarVerification('flavor2Debug')` 之后插入：

```groovy
    tasks.register('verifyNestedTaskRange') {
        group = 'verification'
        description = 'Asserts that consuming :lib-aar through :lib-main does not build descendant merge/rebundle tasks.'
        dependsOn 'assembleFlavor1Debug'
        doLast {
            def forbiddenPrefixes = ['mergeEmbeddedClasses', 'rewriteRClasses', 'reBundleAar', 'mergeClasses', 'transformClassesWithTransformR']
            def offenders = gradle.taskGraph.allTasks.findAll { task ->
                (task.project.path == ':lib-aar' || task.project.path == ':lib-aar2') &&
                        forbiddenPrefixes.any { task.name.startsWith(it) }
            }
            if (!offenders.isEmpty()) {
                throw new GradleException("Nested consumption must not build descendant merge/rebundle tasks, " +
                        "but found: ${offenders*.path.sort().join(', ')}. Each descendant's own assemble must be " +
                        "the only entry point for its merge/rebundle tasks.")
            }
            def rootMerge = gradle.taskGraph.allTasks.findAll { task ->
                task.project.path == ':lib-main' && task.name.startsWith('mergeEmbeddedClasses')
            }
            if (rootMerge.isEmpty()) {
                throw new GradleException("Expected :lib-main to run mergeEmbeddedClasses for its flattened graph.")
            }
            println "[fat-aar][verify] task range OK; :lib-main merge tasks: ${rootMerge*.path.join(', ')}"
        }
    }
```

- [ ] **步骤 2：运行任务范围回归**

```bash
cd example && ./gradlew :lib-main:verifyNestedTaskRange --stacktrace
```

预期：通过；输出 `[fat-aar][verify] task range OK`。

反向验证（故意让断言失败以确认它不是恒真）：临时把 `dependsOn 'assembleFlavor1Debug'` 改为 `dependsOn ':lib-aar:assembleFlavor1Debug'` 运行一次，应报出 `:lib-aar:reBundleAarFlavor1Debug` 等任务路径；确认后改回。

- [ ] **步骤 3：Commit**

```bash
git add example/lib-main/build.gradle
git commit -m "test(nested): assert descendant merge tasks stay out of root build"
```

---

## 阶段三：基线复测

### 任务 8：干净全量基线复测与文档更新

**文件：**
- 修改：`README.md`、`README_CN.md`

- [ ] **步骤 1：在真实 SDK 工程复测**

用与基线相同的口径（干净全量、开启诊断）在真实 SDK 工程执行。基线里嵌套链的顶层是聚合模块；下面的任务名按你工程实际的模块路径与变体名替换：

```bash
./gradlew clean
./gradlew :<聚合模块>:assembleDebug -PfataarDiagnostics=true
```

采集：墙钟总时长、`fat-aar` 任务合计、各被 assemble 模块的 `mergeEmbeddedClasses` / `rewriteRClasses` / `reBundleAar` 执行次数（各模块 `build/intermediates/fat-aar/diagnostics/<variant>.json` 与控制台 `[fat-aar][diagnostics]` 摘要）。

- [ ] **步骤 2：对照验收阈值**

| 指标 | 基线 | 目标 |
| --- | --- | --- |
| 扁平节点的 `mergeEmbeddedClasses` 执行次数 | 每层一次（7 次） | 每个被 assemble 的模块一次 |
| `rewriteRClasses` 执行次数 | 每层一次（7 次） | 每个被 assemble 的模块一次 |
| 被合并的 class 字节总量 | `Σ_层(子树字节)` | `Σ_节点(自有字节)` |
| fat-aar 任务合计 | 920.4 s | 预期下降 ≥ 50% |

未达标时，按落地顺序回看：先确认该模块是否是唯一消费根，再确认 `direct` 过滤是否把深层节点错并入非类内容，最后确认常量池预筛是否真的跳过了非 R class（`rewriteRClasses` 日志会打印 `rewritten` / `passthrough` 计数）。

- [ ] **步骤 3：更新中英文 README**

在 `README_CN.md` 的「多级依赖 / 本地依赖」章节末尾追加：

```markdown
嵌套 `embed` 的消费路径不依赖中间模块的最终产物：根模块在配置阶段展平整棵嵌套图，逐节点采集各模块**自有**的 class，并只做一次类合并与 R 改写。非类内容（资源、Manifest、JNI、consumer ProGuard、本地 jar、SPI 与 Kotlin metadata）仍由直接子模块的薄产物继承，因此不会重复。

由此带来两点行为：

- 构建根模块时不会触发中间模块的类合并与重打包任务；中间模块只有在自身被 `assemble` 或发布时才构建内容完整的最终 AAR。
- 打包耗时不再随嵌套深度线性放大。
```

在 `README.md` 对应章节追加英文译文：

```markdown
Nested `embed` consumption does not depend on intermediate final artifacts. The consuming root flattens the whole nested graph at configuration time, collects each module's **own** classes once, and performs a single class merge and R rewrite. Non-class content (resources, Manifest, JNI, consumer ProGuard, local jars, SPI and Kotlin metadata) is still inherited from direct children's thin AARs and is therefore not duplicated.

Two consequences:

- Building the root does not trigger descendant class-merge or re-bundle tasks; an intermediate module builds its complete final AAR only when it is assembled or published itself.
- Packaging time no longer scales linearly with nesting depth.
```

- [ ] **步骤 4：全量验证与提交**

```bash
./gradlew :source:build
cd example && ./gradlew \
  :lib-aar:verifyNestedFatAarFlavor1Debug \
  :lib-aar:verifyNestedFatAarFlavor2Debug \
  :lib-main:verifyNestedFatAarFlavor1Debug \
  :lib-main:verifyNestedFatAarFlavor2Debug \
  :lib-main:verifyNestedTaskRange \
  :app:assembleDebug --stacktrace
cd .. && git diff --check
```

预期：全部成功；`git diff --check` 无空白错误。

```bash
git add README.md README_CN.md
git commit -m "docs: describe flattened nested embed consumption"
```

---

## 风险与回退

| 风险 | 表现 | 处置 |
| --- | --- | --- |
| 薄产物非类内容并未继承子树 | 根模块最终 AAR 缺少深层 res/Manifest/JNI | 任务 6 步骤 9 的 `:lib-aar` 独立产物回归会先暴露；确认 `processResources/Manifest/JniLibs` 仍在每层对直接子模块生效 |
| 扁平节点重复合并 class | `MergeEmbeddedClassesTask` 抛 `Duplicate class` | 图校验器菱形拒绝未生效；修正 `registerPath` 判定 |
| 常量池预筛漏判 | 运行期 `NoClassDefFoundError: <pkg>.R$xxx` | 预筛只做"跳过"不做"排除"，漏判不会发生；若出现，检查 `extraRepackagedPackages` 是否缺少深层包名 |
| 移除 AGP Transform 后编译期引用失效 | `:app` 编译报找不到 `R` 符号 | `:app` 消费的是最终 AAR，其 `classes.jar` 含聚合 R；若失败，检查 `reBundleAar` 是否真的替换了 `classes.jar` |
| 阶段一后内容缺失 | 验证任务报缺 class | 阶段一必须完成到任务 3 步骤 3 才运行完整回归；未接入 `mFinalClassesJar` 之前不要运行内容验证 |
