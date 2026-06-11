package com.kezong.fataar.tasks

import com.kezong.fataar.AndroidArchiveLibrary
import com.kezong.fataar.FatUtils
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * 严格对照 fit-aar_hook_fix.gradle 实现的 DataBinding 合并任务 (带有日志)
 */
class MergeDataBindingMetadataTask extends DefaultTask {

    @Internal
    Collection<AndroidArchiveLibrary> androidArchiveLibraries

    @Internal
    Object variant

    @TaskAction
    void merge() {
        def variantName = variant.name
        def mergedDbBaseDir = project.file("${project.buildDir}/intermediates/merged_databinding_final/${variantName}")
        def targetLogDir = new File(mergedDbBaseDir, "data-binding-base-class-log")
        def targetDataBindDir = new File(mergedDbBaseDir, "data-binding")

        if (mergedDbBaseDir.exists()) mergedDbBaseDir.deleteDir()
        targetLogDir.mkdirs()
        targetDataBindDir.mkdirs()

        FatUtils.logAnytime("[DataBinding][${variantName}] Starting merge ...")

        // Collect local and remote libraries
        def localLibs = androidArchiveLibraries.findAll { it.embedProject != null }
        def remoteLibs = androidArchiveLibraries.findAll { it.embedProject == null && it.rootFolder != null && it.rootFolder.exists() }
        FatUtils.logAnytime("[DataBinding][${variantName}] Local projects: ${localLibs.collect { it.embedProject.path }.join(', ')}")
        FatUtils.logAnytime("[DataBinding][${variantName}] Remote AAR/JAR libs: ${remoteLibs.size()}")

        // --- 1. 本地子模块处理逻辑 (fit-aar_hook_fix.gradle L49-L62) ---
        localLibs.each { archiveLibrary ->
            Project embedProj = archiveLibrary.embedProject
            if (embedProj.hasProperty('android')) {
                def subIntermediates = embedProj.file("build/intermediates")
                if (subIntermediates.exists()) {
                    subIntermediates.eachFileRecurse { file ->
                        if (file.isFile()) {
                            if (file.name.endsWith("-binding_classes.json")) {
                                project.copy { from file into targetLogDir }
                            }
                            if (file.name.endsWith("-br.bin") || file.name.endsWith("-setter_store.json")) {
                                project.copy { from file into targetDataBindDir }
                            }
                        }
                    }
                }
            }
        }

        // --- 2. 远程 AAR 或本地 AAR 文件处理逻辑 (fit-aar_hook_fix.gradle L65-L75) ---
        remoteLibs.each { archiveLibrary ->
            def rootFolder = archiveLibrary.rootFolder
            if (rootFolder != null && rootFolder.exists()) {
                rootFolder.eachFileRecurse { subFile ->
                    if (subFile.isFile()) {
                        if (subFile.path.contains("data-binding-base-class-log")) {
                            project.copy { from subFile into targetLogDir }
                        } else if (subFile.path.contains("data-binding")) {
                            project.copy { from subFile into targetDataBindDir }
                        }
                    }
                }
            }
        }

        FatUtils.logAnytime("[DataBinding][${variantName}] Merge completed. Logs: ${targetLogDir.listFiles()?.size() ?: 0}, Artifacts: ${targetDataBindDir.listFiles()?.size() ?: 0}")
    }
}
