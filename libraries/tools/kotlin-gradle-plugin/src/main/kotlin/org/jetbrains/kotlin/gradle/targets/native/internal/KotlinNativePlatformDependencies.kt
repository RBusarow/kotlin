/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.native.internal

import org.gradle.api.Project
import org.jetbrains.kotlin.compilerRunner.konanHome
import org.jetbrains.kotlin.daemon.common.toHexString
import org.jetbrains.kotlin.gradle.dsl.multiplatformExtensionOrNull
import org.jetbrains.kotlin.gradle.internal.isInIdeaSync
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider.Companion.KOTLIN_NATIVE_HOME
import org.jetbrains.kotlin.gradle.plugin.compareVersionNumbers
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion
import org.jetbrains.kotlin.gradle.plugin.mpp.CompilationSourceSetUtil
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation
import org.jetbrains.kotlin.gradle.targets.metadata.isKotlinGranularMetadataEnabled
import org.jetbrains.kotlin.gradle.targets.native.internal.NativePlatformDependency.*
import org.jetbrains.kotlin.gradle.utils.SingleWarningPerBuild
import org.jetbrains.kotlin.konan.library.KONAN_DISTRIBUTION_COMMON_LIBS_DIR
import org.jetbrains.kotlin.konan.library.KONAN_DISTRIBUTION_KLIB_DIR
import org.jetbrains.kotlin.konan.library.KONAN_DISTRIBUTION_PLATFORM_LIBS_DIR
import org.jetbrains.kotlin.konan.library.KONAN_STDLIB_NAME
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.utils.addToStdlib.flattenTo
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun Project.setUpKotlinNativePlatformDependencies() {
    check(this == rootProject)

    if (multiplatformExtensionOrNull == null) {
        // not a multiplatform project, nothing to set up
        return
    }

    val kotlinVersion = getKotlinPluginVersion()!!

    // run commonizer only for 1.4+, only for HMPP projects and only on IDE sync
    val allowCommonizer = compareVersionNumbers(kotlinVersion, "1.4") >= 0
            && isKotlinGranularMetadataEnabled
            && isInIdeaSync

    val dependencyResolver = NativePlatformDependencyResolver(this, kotlinVersion)

    findSourceSetsToAddDependencies(allowCommonizer).forEach { (sourceSet: KotlinSourceSet, sourceSetDeps: Set<NativePlatformDependency>) ->
        sourceSetDeps.forEach { sourceSetDep: NativePlatformDependency ->
            dependencyResolver.addForResolve(sourceSetDep) { resolvedFiles: Set<File> ->
                resolvedFiles.forEach { resolvedFile ->
                    dependencies.add(sourceSet.implementationMetadataConfigurationName, dependencies.create(files(resolvedFile)))
                }
            }
        }
    }

    dependencyResolver.resolveAll()
}

private sealed class NativePlatformDependency {
    /* Non-commonized target-neutral libraries taken directly from the Kotlin/Native distribution */
    data class OutOfDistributionCommon(val includeEndorsedLibs: Boolean) : NativePlatformDependency()

    /* Non-commonized libraries for the specific target taken directly from the Kotlin/Native distribution */
    data class OutOfDistributionPlatform(val target: KonanTarget) : NativePlatformDependency()

    /* Commonized (common) libraries */
    data class CommonizedCommon(val targets: Set<KonanTarget>) : NativePlatformDependency()

    /* Commonized libraries for a specific target platform */
    data class CommonizedPlatform(val target: KonanTarget, val common: CommonizedCommon) : NativePlatformDependency()
}

private class NativePlatformDependencyResolver(val project: Project, val kotlinVersion: String) {
    private val distributionDir = File(project.konanHome)
    private val distributionLibsDir = distributionDir.resolve(KONAN_DISTRIBUTION_KLIB_DIR)

    private var alreadyResolved = false
    private val dependencies = mutableMapOf<NativePlatformDependency, MutableList<(Set<File>) -> Unit>>()

    fun addForResolve(dependency: NativePlatformDependency, whenResolved: (Set<File>) -> Unit) {
        check(!alreadyResolved)
        dependencies.computeIfAbsent(dependency) { mutableListOf() }.add(whenResolved)
    }

    fun resolveAll() {
        check(!alreadyResolved)
        alreadyResolved = true

        // first, run commonization
        val targetGroups: List<Pair<CommonizedCommon, Set<KonanTarget>>> = dependencies.keys.filterIsInstance<CommonizedCommon>()
            .map { it to it.targets }

        val commonizedLibsDirs: Map<CommonizedCommon, File> =
            runCommonizer(
                distributionDir = distributionDir,
                baseDestinationDir = project.buildDir.resolve("commonizedLibraries"),
                targetGroups = targetGroups.map { it.second },
                kotlinVersion = kotlinVersion
            ).mapIndexed { index: Int, commonizedLibsDir: File ->
                targetGroups[index].first to commonizedLibsDir
            }.toMap()

        // then, resolve dependencies one by one
        dependencies.forEach { (dependency, actions) ->
            val libs = when (dependency) {
                is OutOfDistributionCommon -> {
                    /* stdlib, endorsed libs */
                    var hasStdlib = false

                    val libs = libsInCommonDir(distributionLibsDir) { dir ->
                        val isStdlib = dir.endsWith(KONAN_STDLIB_NAME)
                        hasStdlib = hasStdlib || isStdlib

                        return@libsInCommonDir isStdlib || dependency.includeEndorsedLibs
                    }

                    if (!hasStdlib) warnAboutMissingNativeStdlib()

                    libs
                }

                is OutOfDistributionPlatform -> {
                    /* platform libs for a specific target */
                    libsInPlatformDir(distributionLibsDir, dependency.target)
                }

                is CommonizedCommon -> {
                    /* commonized platform libs with expect declarations */
                    val commonizedLibsDir = commonizedLibsDirs.getValue(dependency)
                    libsInCommonDir(commonizedLibsDir)
                }

                is CommonizedPlatform -> {
                    /* commonized platform libs with actual declarations */
                    val commonizedLibsDir = commonizedLibsDirs.getValue(dependency.common)
                    libsInPlatformDir(commonizedLibsDir, dependency.target)
                }
            }

            actions.forEach { it(libs) }
        }
    }

    private fun warnAboutMissingNativeStdlib() {
        if (!project.hasProperty("kotlin.native.nostdlib")) {
            SingleWarningPerBuild.show(
                project,
                buildString {
                    append(NO_NATIVE_STDLIB_WARNING)
                    if (PropertiesProvider(project).nativeHome != null)
                        append(NO_NATIVE_STDLIB_PROPERTY_WARNING)
                }
            )
        }
    }

    companion object {
        private fun libsInCommonDir(basePath: File, predicate: (File) -> Boolean = { true }) =
            basePath.resolve(KONAN_DISTRIBUTION_COMMON_LIBS_DIR).listFiles()?.filter { predicate(it) }?.toSet() ?: emptySet()

        private fun libsInPlatformDir(basePath: File, target: KonanTarget) =
            basePath.resolve(KONAN_DISTRIBUTION_PLATFORM_LIBS_DIR).resolve(target.name).listFiles()?.toSet() ?: emptySet()
    }
}

private fun Project.findSourceSetsToAddDependencies(allowCommonizer: Boolean): Map<KotlinSourceSet, Set<NativePlatformDependency>> {
    val sourceSetsToAddDeps = mutableMapOf<KotlinSourceSet, Set<NativePlatformDependency>>()
    if (allowCommonizer) {
        sourceSetsToAddDeps += findSourceSetsToAddCommonizedPlatformDependencies()
    }

    val compilationsBySourceSets = CompilationSourceSetUtil.compilationsBySourceSets(this)
    val nativeCompilations = compilationsBySourceSets.values.flattenTo(mutableSetOf()).filterIsInstance<KotlinNativeCompilation>()

    nativeCompilations.associate { it.defaultSourceSet to (it.konanTarget to it.enableEndorsedLibs) }
        .forEach { (defaultSourceSet, details) ->
            if (defaultSourceSet !in sourceSetsToAddDeps) {
                val (target, includeEndorsedLibs) = details
                sourceSetsToAddDeps[defaultSourceSet] = setOf(
                    OutOfDistributionCommon(includeEndorsedLibs),
                    OutOfDistributionPlatform(target)
                )
            }
        }

    return sourceSetsToAddDeps
}

private fun Project.findSourceSetsToAddCommonizedPlatformDependencies(): Map<KotlinSourceSet, Set<NativePlatformDependency>> {
    val sourceSetsToAddDeps = mutableMapOf<KotlinSourceSet, Set<NativePlatformDependency>>()

    val compilationsBySourceSets = CompilationSourceSetUtil.compilationsBySourceSets(this)
    val nativeCompilations = compilationsBySourceSets.values.flattenTo(mutableSetOf()).filterIsInstance<KotlinNativeCompilation>()

    nativeCompilations.forEach { nativeCompilation ->
        // consider source sets in compilation only one step above the default source set
        // TODO: reconsider this restriction
        val commonSourceSetCandidates = nativeCompilation.allKotlinSourceSets intersect nativeCompilation.defaultSourceSet.dependsOn

        commonSourceSetCandidates.forEach sourceSet@{ sourceSet ->
            if (sourceSet.name == KotlinSourceSet.COMMON_MAIN_SOURCE_SET_NAME
                || sourceSet.name == KotlinSourceSet.COMMON_TEST_SOURCE_SET_NAME
            ) {
                // exclude the most common source sets
                return@sourceSet
            }

            if (sourceSet in sourceSetsToAddDeps) {
                // already processed
                return@sourceSet
            }

            val leafSourceSets = mutableMapOf<KotlinSourceSet, Pair<KonanTarget, /* include endorsed? */ Boolean>>()

            for (compilation in compilationsBySourceSets.getValue(sourceSet)) {
                if (compilation !is KotlinNativeCompilation) {
                    // the source set participates in non-native compilation
                    return@sourceSet
                }

                val defaultSourceSet = compilation.defaultSourceSet
                if (defaultSourceSet == sourceSet) {
                    // there is a compilation where the source set is the default source set
                    return@sourceSet
                }

                leafSourceSets[defaultSourceSet] = compilation.konanTarget to compilation.enableEndorsedLibs
            }

            val allTargets = leafSourceSets.values.mapTo(mutableSetOf()) { /* target */ it.first }
            if (allTargets.isEmpty())
                return@sourceSet

            val commonizedCommonDep = CommonizedCommon(allTargets)
            val includeEndorsedLibsToCommonSourceSet = leafSourceSets.values.all { /* include endorsed? */ it.second }

            sourceSetsToAddDeps[sourceSet] = setOf(
                OutOfDistributionCommon(includeEndorsedLibsToCommonSourceSet),
                commonizedCommonDep
            )

            leafSourceSets.forEach { (leafSourceSet, details) ->
                val existingDep = sourceSetsToAddDeps[leafSourceSet]
                if (existingDep == null) {
                    val (target, includeEndorsedLibs) = details
                    val commonizedPlatformDep = CommonizedPlatform(target, commonizedCommonDep)

                    sourceSetsToAddDeps[leafSourceSet] = setOf(
                        OutOfDistributionCommon(includeEndorsedLibs),
                        commonizedPlatformDep
                    )
                } /*else if (existingDep != leafDep) {
                    // actually, no action required
                }*/
            }
        }
    }

    return sourceSetsToAddDeps
}

private fun runCommonizer(
    distributionDir: File,
    baseDestinationDir: File,
    targetGroups: List<Set<KonanTarget>>,
    kotlinVersion: String
): List<File> {
    val actionsBefore = mutableListOf<() -> Unit>()
    val commandLineParameters = StringBuilder()
    val actionsAfter = mutableListOf<() -> Unit>()

    val result = targetGroups.map { targets ->
        if (targets.size == 1) {
            // no need to commonize, just use the libraries from the distribution
            distributionDir.resolve(KONAN_DISTRIBUTION_KLIB_DIR)
        } else {
            // naive up-to-date check
            val definitelyNotUpToDate = when {
                kotlinVersion.endsWith("SNAPSHOT", ignoreCase = true) -> {
                    // "X.Y.Z-SNAPSHOT" is not enough to uniquely identify the concrete version of Kotlin plugin,
                    // therefore lets assume that it's always not up to date
                    true
                }

                !distributionDir.startsWith(File(System.getProperty("user.home")).resolve(".konan")) -> {
                    // a distribution installed in non-standard location, probably built by user
                    true
                }

                else -> false
            }

            // need stable order of targets for consistency
            val orderedTargets = targets.sortedBy { it.name }

            val discriminator = buildString {
                append(kotlinVersion.onlySafeCharacters)
                append("-")
                append(distributionDir.path.md5String)
                append("-")
                orderedTargets.joinTo(this, separator = "-")
            }

            val destinationDir = baseDestinationDir.resolve(discriminator)
            if (definitelyNotUpToDate || !destinationDir.isDirectory) {
                val destinationTmpDir = destinationDir.parentFile.resolve(destinationDir.name + ".tmp")

                actionsBefore.add { destinationDir.deleteRecursively() }
                commandLineParameters.append(
                    "native-dist-commonize -distribution-path $distributionDir -output-path $destinationTmpDir -targets $orderedTargets "
                )
                actionsAfter.add { destinationTmpDir.renameTo(destinationDir) }
            }

            destinationDir
        }
    }

    actionsBefore.forEach { it() }
    if (commandLineParameters.isNotEmpty()) {
        // TODO: call the commonizer
    }
    actionsAfter.forEach { it() }

    return result
}

private val String.md5String
    get() = MessageDigest.getInstance("MD5").digest(toByteArray(StandardCharsets.UTF_8)).toHexString()

private val String.onlySafeCharacters
    get() = StringBuilder().also { builder ->
        for (ch in this) {
            builder.append(
                when (ch) {
                    in '0'..'9', in 'a'..'Z', in 'A'..'Z' -> ch
                    else -> '_'
                }
            )
        }
    }.toString()

internal const val NO_NATIVE_STDLIB_WARNING =
    "The Kotlin/Native distribution used in this build does not provide the standard library. "

internal const val NO_NATIVE_STDLIB_PROPERTY_WARNING =
    "Make sure that the '$KOTLIN_NATIVE_HOME' property points to a valid Kotlin/Native distribution."
