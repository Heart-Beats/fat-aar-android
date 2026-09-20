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
        Set<String> writtenNames = new LinkedHashSet<>()
        outputJar.parentFile.mkdirs()
        ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputJar)))
        long copiedEntries = 0L
        try {
            copiedEntries += copyJar(ownClassesJar, output, owners, writtenNames, false, false).written
            Set<File> embedded = (embeddedClassesJars ?: Collections.emptySet()) as LinkedHashSet<File>
            embedded.each { File jar ->
                if (!jar.isFile()) {
                    // 兼容：插件一直允许 embed 不含 classes.jar 的 aar（纯资源 / 纯 so 的本地 aar）
                    FatUtils.logInfo("[fat-aar][classes] skip archive without classes.jar: '${jar.absolutePath}'")
                    return
                }
                Map result = copyJar(jar, output, owners, writtenNames, true, true)
                copiedEntries += result.written
                if (result.written == 0L && result.sourceClasses > 0L) {
                    throw new GradleException("Embedded archive contributed no class: '${jar.absolutePath}'. " +
                            "An empty contribution means the nested graph lost a descendant; check that the " +
                            "flattened node list covers every reachable Android project.")
                }
            }
            (extraClassesJars ?: Collections.emptySet()).each { File jar ->
                if (jar.isFile()) {
                    copiedEntries += copyJar(jar, output, owners, writtenNames, true, true).written
                }
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

    /**
     * @return written 写出的条目数；sourceClasses 源 jar 内的 .class 条目总数（含被判重的）
     */
    private Map copyJar(File jar,
                        ZipOutputStream output,
                        Map<String, String> owners,
                        Set<String> writtenNames,
                        boolean excludeMetaInf,
                        boolean strictDuplicates) {
        long written = 0L
        long sourceClasses = 0L
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
                boolean isClass = entry.name.endsWith('.class')
                if (isClass) {
                    sourceClasses++
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
                if (!writtenNames.add(entry.name)) {
                    // 非 class 条目重名不会改变语义（原实现是"后覆盖前"），但流水线不允许重复 zip 条目
                    FatUtils.logInfo("[fat-aar][classes] skip duplicated non-class entry '${entry.name}' " +
                            "from '${jar.absolutePath}'")
                    continue
                }
                ZipEntry outEntry = new ZipEntry(entry.name)
                if (entry.time > 0L) {
                    outEntry.time = entry.time
                }
                output.putNextEntry(outEntry)
                InputStream input = zip.getInputStream(entry)
                try {
                    byte[] buffer = new byte[8192]
                    int read
                    while ((read = input.read(buffer)) >= 0) {
                        output.write(buffer, 0, read)
                    }
                } finally {
                    input.close()
                }
                output.closeEntry()
                written++
            }
        } finally {
            zip.close()
        }
        return [written: written, sourceClasses: sourceClasses]
    }
}
