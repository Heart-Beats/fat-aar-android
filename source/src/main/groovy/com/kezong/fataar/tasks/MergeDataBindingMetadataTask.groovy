package com.kezong.fataar.tasks

import com.kezong.fataar.AndroidArchiveLibrary
import com.kezong.fataar.FatUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 从当前变体实际使用的 AAR 内容合并 DataBinding metadata。
 */
class MergeDataBindingMetadataTask extends DefaultTask {

    @Internal
    Object variant

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    Collection<File> metadataRoots = Collections.emptyList()

    @OutputDirectory
    File mergedDbBaseDir

    @TaskAction
    void merge() {
        File targetLogDir = new File(mergedDbBaseDir, "data-binding-base-class-log")
        File targetDataBindDir = new File(mergedDbBaseDir, "data-binding")

        if (mergedDbBaseDir.exists()) {
            mergedDbBaseDir.deleteDir()
        }
        targetLogDir.mkdirs()
        targetDataBindDir.mkdirs()

        FatUtils.logAnytime("[DataBinding][${variant.name}] Starting merge ...")
        Map<String, File> sources = new LinkedHashMap<>()
        for (File root : metadataRoots) {
            mergeDirectory(root, "data-binding", targetDataBindDir, sources)
            mergeDirectory(root, "data-binding-base-class-log", targetLogDir, sources)
        }
        FatUtils.logAnytime("[DataBinding][${variant.name}] Merge completed. Logs: " +
                "${targetLogDir.listFiles()?.size() ?: 0}, Artifacts: ${targetDataBindDir.listFiles()?.size() ?: 0}")
    }

    private void mergeDirectory(File root, String entryDirectory, File targetDirectory, Map<String, File> sources) {
        File sourceDirectory = new File(root, entryDirectory)
        if (!sourceDirectory.isDirectory()) {
            return
        }
        for (File source : project.fileTree(sourceDirectory).files) {
            String relativePath = sourceDirectory.toPath().relativize(source.toPath()).toString().replace('\\', '/')
            String entryPath = "${entryDirectory}/${relativePath}"
            File previous = sources.get(entryPath)
            if (previous != null) {
                if (!Arrays.equals(previous.bytes, source.bytes)) {
                    throw new GradleException("Conflicting DataBinding metadata '${entryPath}' from " +
                            "'${previous.absolutePath}' and '${source.absolutePath}'.")
                }
                continue
            }

            File target = new File(targetDirectory, relativePath)
            target.parentFile.mkdirs()
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            sources.put(entryPath, source)
        }
    }
}
