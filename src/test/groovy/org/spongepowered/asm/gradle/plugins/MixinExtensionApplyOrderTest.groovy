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
 * Covers the claim that the Kotlin plugins may be applied before or after this
 * one. The detection is done with <tt>pluginManager.withPlugin</tt>, so it must
 * work whichever way round the plugins are applied, including the settings which
 * are changed at the moment a plugin is applied.
 *
 * The Kotlin plugins are faked and registered under their real ids so that the
 * plugin manager fires the same callbacks it would in a real build.
 */
class MixinExtensionApplyOrderTest {

    private static final String KOTLIN_ID = 'org.jetbrains.kotlin.jvm'
    private static final String KAPT_ID = 'kotlin-kapt'

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
        Project project = this.projectWithJava()
        project.pluginManager.apply(KOTLIN_ID)
        project.pluginManager.apply(KAPT_ID)

        MixinExtension extension = new MixinExtension(project)

        assertTrue 'kapt must be detected', extension.kaptApplied
        assertTrue 'the Kotlin plugin must be detected', extension.kotlinApplied
    }

    /**
     * The reverse order: this plugin is applied first, so the callbacks have to
     * fire later, when the Kotlin plugins show up.
     */
    @Test
    void detectsKaptAppliedAfterMixinGradle() {
        Project project = this.projectWithJava()

        MixinExtension extension = new MixinExtension(project)
        assertFalse 'nothing should be detected yet', extension.kaptApplied

        project.pluginManager.apply(KOTLIN_ID)
        project.pluginManager.apply(KAPT_ID)

        assertTrue 'kapt applied later must still be detected', extension.kaptApplied
        assertTrue 'the Kotlin plugin applied later must still be detected', extension.kotlinApplied
    }

    /**
     * When kapt is applied after this plugin the setting which stops kapt from
     * disabling annotation processing for javac still has to be applied, which
     * means the kapt extension has to be found even though the extension was
     * created earlier.
     */
    @Test
    void enablesJavacAnnotationProcessingWhicheverOrderThePluginsAreApplied() {
        Project kaptFirst = this.projectWithJava()
        kaptFirst.pluginManager.apply(KAPT_ID)
        new MixinExtension(kaptFirst)

        Project mixinFirst = this.projectWithJava()
        new MixinExtension(mixinFirst)
        mixinFirst.pluginManager.apply(KAPT_ID)

        assertTrue 'kapt applied first',
                kaptFirst.extensions.getByName('kapt').keepJavacAnnotationProcessors
        assertTrue 'kapt applied last',
                mixinFirst.extensions.getByName('kapt').keepJavacAnnotationProcessors
    }

    /**
     * The kapt task must be found for the sourceSet either way, otherwise a
     * sourceSet whose Kotlin sources are processed by kapt would be treated as
     * Java only.
     */
    @Test
    void findsTheKaptTaskWhicheverOrderThePluginsAreApplied() {
        Project kaptFirst = this.projectWithJava()
        kaptFirst.pluginManager.apply(KOTLIN_ID)
        kaptFirst.pluginManager.apply(KAPT_ID)
        MixinExtension first = new MixinExtension(kaptFirst)

        Project mixinFirst = this.projectWithJava()
        MixinExtension second = new MixinExtension(mixinFirst)
        mixinFirst.pluginManager.apply(KOTLIN_ID)
        mixinFirst.pluginManager.apply(KAPT_ID)

        SourceSetContainer before = this.sourceSetsOf(kaptFirst)
        SourceSetContainer after = this.sourceSetsOf(mixinFirst)

        assertEquals 2, first.getProcessorTasks(before.getByName('main')).size()
        assertEquals 2, second.getProcessorTasks(after.getByName('main')).size()
        assertEquals 'kaptKotlin',
                second.getKaptTask(after.getByName('main')).name
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