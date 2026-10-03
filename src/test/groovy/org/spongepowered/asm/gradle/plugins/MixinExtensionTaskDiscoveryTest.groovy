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
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertNull
import static org.junit.Assert.assertSame
import static org.junit.Assert.assertTrue

/**
 * Covers the discovery of the tasks which run the Mixin annotation processor for
 * a sourceSet. A sourceSet containing both Java and Kotlin sources is processed
 * by two of them, and each has to be found for the sourceSet's refmap to
 * describe every mixin in it.
 *
 * The tasks are faked, because the real ones can only be created by the Kotlin
 * Gradle plugin, which must not be on this plugin's classpath. What matters
 * here is the feature detection: kapt tasks are recognised by the properties
 * they expose rather than by their class.
 */
class MixinExtensionTaskDiscoveryTest {

    private Project project
    private MixinExtension extension

    @Before
    void setUp() {
        this.project = ProjectBuilder.builder().build()
        this.project.pluginManager.apply('java')
        this.extension = new MixinExtension(this.project)
    }

    private SourceSetContainer getSourceSets() {
        return this.project.convention.getPlugin(JavaPluginConvention).sourceSets
    }

    private SourceSet getMain() {
        return this.sourceSets.getByName('main')
    }

    /**
     * Creates a task which looks like a kapt task for the given sourceSet.
     */
    private Task createKaptTask(String name, String sourceSetName) {
        Task task = this.project.tasks.create(name)
        task.ext.sourceSetName = this.project.objects.property(String)
        task.ext.sourceSetName.set(sourceSetName)
        task.ext.kaptPluginOptions = []
        return task
    }

    /**
     * Creates the kapt stub generation task for a sourceSet. It reports the
     * sourceSet name but does not run the annotation processor, so it must not
     * be treated as a processor task.
     */
    private Task createKaptStubTask(String name, String sourceSetName) {
        Task task = this.project.tasks.create(name)
        task.ext.sourceSetName = this.project.objects.property(String)
        task.ext.sourceSetName.set(sourceSetName)
        return task
    }

    /**
     * The point of the whole change: a sourceSet with both Java and Kotlin
     * sources is processed by javac and by kapt, and both must be found so that
     * both refmaps are merged and both sets of SRGs reach reobf.
     */
    @Test
    void findsJavaCompileAndKaptTaskForTheSameSourceSet() {
        Task kaptKotlin = this.createKaptTask('kaptKotlin', 'main')
        this.extension.kaptApplied = true

        List<Task> processorTasks = this.extension.getProcessorTasks(this.main)

        assertEquals 'both javac and kapt must process the sourceSet', 2, processorTasks.size()
        assertSame 'javac must be included', this.project.tasks.getByName('compileJava'), processorTasks[0]
        assertSame 'kapt must be included', kaptKotlin, processorTasks[1]
    }

    /**
     * kapt names the task for the 'main' sourceSet 'kaptKotlin', so the task
     * cannot be found by the sourceSet's compile task name.
     */
    @Test
    void findsKaptTaskByTheSourceSetNameItReports() {
        this.extension.kaptApplied = true
        Task kaptKotlin = this.createKaptTask('kaptKotlin', 'main')

        assertSame kaptKotlin, this.extension.getKaptTask(this.main)
    }

    /**
     * A project with more than one sourceSet has one kapt task each, and each
     * sourceSet must be matched with its own.
     */
    @Test
    void doesNotConfuseTheKaptTasksOfDifferentSourceSets() {
        this.extension.kaptApplied = true
        this.createKaptTask('kaptKotlin', 'main')
        Task kaptTestKotlin = this.createKaptTask('kaptTestKotlin', 'test')

        assertSame 'the test sourceSet must get the test kapt task',
                kaptTestKotlin, this.extension.getKaptTask(this.sourceSets.getByName('test'))
        assertEquals 'main must get the main kapt task, not the test one',
                'kaptKotlin', this.extension.getKaptTask(this.main).name
    }

    /**
     * The kapt stub generation task runs the Kotlin compiler but not the
     * annotation processor, so it must not be treated as a processor task even
     * though it reports a sourceSet name.
     */
    @Test
    void ignoresKaptStubGenerationTask() {
        this.extension.kaptApplied = true
        this.createKaptStubTask('kaptGenerateStubsKotlin', 'main')
        this.createKaptTask('kaptKotlin', 'main')

        assertEquals 'kaptKotlin', this.extension.getKaptTask(this.main).name
    }

    /**
     * javac does not run the annotation processor over Kotlin sources, so it is
     * never a kapt task.
     */
    @Test
    void doesNotTreatJavaCompileAsAKaptTask() {
        assertFalse this.extension.isKaptTask(this.project.tasks.getByName('compileJava'))
    }

    @Test
    void treatsATaskWithKaptPluginOptionsAsAKaptTask() {
        assertTrue this.extension.isKaptTask(this.createKaptTask('kaptKotlin', 'main'))
    }

    /**
     * Without kapt there is nothing to process the Kotlin sources, so only
     * javac must be configured and no attempt may be made to find a kapt task.
     */
    @Test
    void configuresOnlyJavaCompileWhenKaptIsNotApplied() {
        this.createKaptTask('kaptKotlin', 'main')
        this.extension.kaptApplied = false

        List<Task> processorTasks = this.extension.getProcessorTasks(this.main)

        assertEquals 1, processorTasks.size()
        assertSame this.project.tasks.getByName('compileJava'), processorTasks[0]
        assertNull 'no kapt task must be reported when kapt is not applied', this.extension.getKaptTask(this.main)
    }

    /**
     * A task which reports no sourceSet name must not be claimed for any
     * sourceSet, since matching it to the wrong one would merge the wrong
     * refmap.
     */
    @Test
    void ignoresKaptTaskWithoutASourceSetName() {
        this.extension.kaptApplied = true
        Task anonymous = this.project.tasks.create('kaptSomething')
        anonymous.ext.kaptPluginOptions = []

        assertNull this.extension.getKaptSourceSetName(anonymous)
        assertNull this.extension.getKaptTask(this.main)
    }
}
