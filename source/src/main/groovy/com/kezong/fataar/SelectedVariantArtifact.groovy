package com.kezong.fataar

import com.android.build.gradle.api.LibraryVariant
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

class SelectedVariantArtifact {

    Project project
    LibraryVariant variant
    TaskProvider bundleTask
    File outputFile

    SelectedVariantArtifact(Project project, LibraryVariant variant, TaskProvider bundleTask, File outputFile) {
        this.project = project
        this.variant = variant
        this.bundleTask = bundleTask
        this.outputFile = outputFile
    }
}
