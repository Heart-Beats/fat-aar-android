package com.kezong.fataar;

import com.android.build.gradle.api.LibraryVariant;

import org.gradle.api.Project;

/**
 * Temp directory used by fat-aar
 */
class DirectoryManager {

    private static final String RE_BUNDLE_FOLDER = "aar_rebundle";

    private static final String INTERMEDIATES_TEMP_FOLDER = "fat-aar";

    private static Project sProject;

    static void attach(Project project) {
        sProject = project;
    }

    static File getReBundleDirectory(Project project, LibraryVariant variant) {
        return project.file("${project.buildDir}/intermediates/${INTERMEDIATES_TEMP_FOLDER}/rebundle/${variant.name}")
    }

    static File getFinalAarFile(Project project, LibraryVariant variant, File sourceAar) {
        return project.file("${project.buildDir}/outputs/${INTERMEDIATES_TEMP_FOLDER}/${variant.name}/${sourceAar.name}")
    }

    static File getRJavaDirectory(Project project, LibraryVariant variant) {
        return project.file("${project.buildDir}/intermediates/${INTERMEDIATES_TEMP_FOLDER}/r/${variant.name}")
    }

    static File getRClassDirectory(Project project, LibraryVariant variant) {
        return project.file("${project.buildDir}/intermediates/${INTERMEDIATES_TEMP_FOLDER}/r-class/${variant.name}")
    }

    static File getRJarDirectory(Project project, LibraryVariant variant) {
        return new File(getReBundleDirectory(project, variant), "libs")
    }

    static File getMergeClassDirectory(Project project, LibraryVariant variant) {
        return project.file("${project.buildDir}/intermediates/${INTERMEDIATES_TEMP_FOLDER}/merge_classes/${variant.name}")
    }

    static File getKotlinMetaDirectory(Project project, LibraryVariant variant) {
        return project.file("${project.buildDir}/tmp/kotlin-classes/${variant.name}/META-INF")
    }

    // Kept for compatibility with existing callers while they migrate to explicit projects.
    static File getReBundleDirectory(LibraryVariant variant) {
        return getReBundleDirectory(sProject, variant)
    }

    static File getRJavaDirectory(LibraryVariant variant) {
        return getRJavaDirectory(sProject, variant)
    }

    static File getRClassDirectory(LibraryVariant variant) {
        return getRClassDirectory(sProject, variant)
    }

    static File getRJarDirectory(LibraryVariant variant) {
        return getRJarDirectory(sProject, variant)
    }

    static File getMergeClassDirectory(LibraryVariant variant) {
        return getMergeClassDirectory(sProject, variant)
    }

    static File getKotlinMetaDirectory(LibraryVariant variant) {
        return getKotlinMetaDirectory(sProject, variant)
    }
}
