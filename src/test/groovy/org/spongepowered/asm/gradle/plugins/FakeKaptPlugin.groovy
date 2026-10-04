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

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task

/**
 * Stands in for the Kotlin Gradle plugin's kapt plugin, registered under the
 * real plugin id so that the plugin manager fires the callbacks this plugin
 * registers for it. Only the parts this plugin actually touches are provided:
 * the <tt>kapt</tt> extension and a kapt task per sourceSet.
 *
 * The real plugin cannot be used, because it would put the Kotlin Gradle
 * plugin on the classpath, which this plugin deliberately avoids.
 */
class FakeKaptPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {
        project.extensions.create('kapt', FakeKaptExtension)

        // Mirrors kapt registering a task per sourceSet which runs the
        // annotation processor, named after the Kotlin compilation rather than
        // after the sourceSet.
        project.plugins.withId('java') {
            project.sourceSets.all { set ->
                String suffix = set.name == 'main' ? 'Kotlin' : set.name.capitalize() + 'Kotlin'
                Task kaptTask = project.tasks.create("kapt${suffix}")
                kaptTask.ext.sourceSetName = project.objects.property(String)
                kaptTask.ext.sourceSetName.set(set.name)
                kaptTask.ext.kaptPluginOptions = []
            }
        }
    }
}