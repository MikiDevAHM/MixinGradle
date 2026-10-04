/*
 * This file is part of MixinGradle, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.asm.gradle.plugins

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertTrue

/**
 * Covers the two claims the Kotlin support rests on: that the Kotlin plugins
 * may be applied before or after this one, and that they are detected however
 * they are applied. The detection is done with
 * <tt>pluginManager.withPlugin</tt>, so it has to work whichever way round the
 * plugins are applied.
 *
 * Every id the Kotlin Gradle plugin is applied by is exercised, because a build
 * which applies one that is not handled has its Kotlin sources skipped
 * silently: the warning which would report that is itself only issued once the
 * Kotlin plugin has been detected. Both of the Kotlin plugin's ids resolve to
 * the same plugin, so handling either one is enough, and that is what these
 * tests hold this plugin to.
 *
 * The Kotlin plugins are faked and registered under their real ids so that the
 * plugin manager fires the same callbacks it would in a real build. The real
 * plugins cannot be used, because that would put the Kotlin Gradle plugin on
 * the buildscript classpath.
 */
class MixinExtensionApplyOrderTest {

    /**
     * Every id the Kotlin JVM plugin is applied by, and the kapt plugin is
     * applied by. Both resolve to the same plugin classes, so all of them have
     * to be detected.
     */
    private static final List<String> KOTLIN_IDS = ['org.jetbrains.kotlin.jvm', 'kotlin']
    private static final List<String> KAPT_IDS = ['kotlin-kapt', 'org.jetbrains.kotlin.kapt']

    private Project projectWithJava() {
        Project project = ProjectBuilder.builder().build()
        project.pluginManager.apply('java')
        return project
    }

    private SourceSetContainer sourceSetsOf(Project project) {
        return project.extensions.getByType(JavaPluginExtension).sourceSets
    }

    /**
     * The order used by the samples: the Kotlin plugins first, this one last.
     */
    @Test
    void detectsKaptAppliedBeforeMixinGradle() {
        KOTLIN_IDS.each { kotlinId ->
            KAPT_IDS.each { kaptId ->
                Project project = this.projectWithJava()
                project.pluginManager.apply(kotlinId)
                project.pluginManager.apply(kaptId)

                MixinExtension extension = new MixinExtension(project)

                assertTrue "kapt must be detected when applied as '$kaptId'", extension.kaptApplied
                assertTrue "the Kotlin plugin must be detected when applied as '$kotlinId'",
                        extension.kotlinApplied
            }
        }
    }

    /**
     * The reverse order: this plugin is applied first, so the callbacks have to
     * fire later, when the Kotlin plugins show up.
     */
    @Test
    void detectsKaptAppliedAfterMixinGradle() {
        KOTLIN_IDS.each { kotlinId ->
            KAPT_IDS.each { kaptId ->
                Project project = this.projectWithJava()

                MixinExtension extension = new MixinExtension(project)
                assertFalse "nothing should be detected yet, '$kaptId'", extension.kaptApplied

                project.pluginManager.apply(kotlinId)
                project.pluginManager.apply(kaptId)

                assertTrue "kapt applied later as '$kaptId' must still be detected",
                        extension.kaptApplied
                assertTrue "the Kotlin plugin applied later as '$kotlinId' must still be detected",
                        extension.kotlinApplied
            }
        }
    }

    /**
     * When kapt is applied after this plugin the setting which stops kapt from
     * disabling annotation processing for javac still has to be applied, which
     * means the kapt extension has to be found even though the extension was
     * created earlier.
     */
    @Test
    void enablesJavacAnnotationProcessingWhicheverOrderThePluginsAreApplied() {
        KAPT_IDS.each { kaptId ->
            Project kaptFirst = this.projectWithJava()
            kaptFirst.pluginManager.apply(kaptId)
            new MixinExtension(kaptFirst)

            Project mixinFirst = this.projectWithJava()
            new MixinExtension(mixinFirst)
            mixinFirst.pluginManager.apply(kaptId)

            assertTrue "kapt applied first as '$kaptId'",
                    kaptFirst.extensions.getByName('kapt').keepJavacAnnotationProcessors
            assertTrue "kapt applied last as '$kaptId'",
                    mixinFirst.extensions.getByName('kapt').keepJavacAnnotationProcessors
        }
    }

    /**
     * The kapt task must be found for the sourceSet either way, otherwise a
     * sourceSet whose Kotlin sources are processed by kapt would be treated as
     * Java only.
     */
    @Test
    void findsTheKaptTaskWhicheverOrderThePluginsAreApplied() {
        KAPT_IDS.each { kaptId ->
            Project kaptFirst = this.projectWithJava()
            kaptFirst.pluginManager.apply(kaptId)
            MixinExtension first = new MixinExtension(kaptFirst)

            Project mixinFirst = this.projectWithJava()
            MixinExtension second = new MixinExtension(mixinFirst)
            mixinFirst.pluginManager.apply(kaptId)

            SourceSetContainer before = this.sourceSetsOf(kaptFirst)
            SourceSetContainer after = this.sourceSetsOf(mixinFirst)

            assertEquals "kapt applied first as '$kaptId'", 2,
                    first.getProcessorTasks(before.getByName('main')).size()
            assertEquals "kapt applied last as '$kaptId'", 2,
                    second.getProcessorTasks(after.getByName('main')).size()
            assertEquals "kapt applied last as '$kaptId'", 'kaptKotlin',
                    second.getKaptTask(after.getByName('main')).name
        }
    }

    /**
     * A project without the Kotlin plugins must be left alone, so that applying
     * this plugin to a Java only project does not warn about missing kapt.
     */
    @Test
    void leavesAJavaOnlyProjectAlone() {
        Project project = this.projectWithJava()
        MixinExtension extension = new MixinExtension(project)

        assertFalse extension.kaptApplied
        assertFalse extension.kotlinApplied
        assertEquals 1, extension.getProcessorTasks(this.sourceSetsOf(project).getByName('main')).size()
    }
}