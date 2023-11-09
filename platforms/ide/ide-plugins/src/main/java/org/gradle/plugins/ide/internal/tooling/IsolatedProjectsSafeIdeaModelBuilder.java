/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.plugins.ide.internal.tooling;

import com.google.common.collect.Streams;
import org.gradle.api.JavaVersion;
import org.gradle.api.NonNullApi;
import org.gradle.api.Project;
import org.gradle.plugins.ide.idea.IdeaPlugin;
import org.gradle.plugins.ide.idea.internal.IdeaModuleSupport;
import org.gradle.plugins.ide.idea.internal.IdeaProjectInternal;
import org.gradle.plugins.ide.idea.model.IdeaLanguageLevel;
import org.gradle.plugins.ide.idea.model.IdeaModel;
import org.gradle.plugins.ide.internal.tooling.idea.DefaultIdeaJavaLanguageSettings;
import org.gradle.plugins.ide.internal.tooling.idea.DefaultIdeaLanguageLevel;
import org.gradle.plugins.ide.internal.tooling.idea.DefaultIdeaModule;
import org.gradle.plugins.ide.internal.tooling.idea.DefaultIdeaProject;
import org.gradle.plugins.ide.internal.tooling.java.DefaultInstalledJdk;
import org.gradle.plugins.ide.internal.tooling.model.DefaultGradleProject;
import org.gradle.plugins.ide.internal.tooling.idea.IsolatedIdeaModuleInternal;
import org.gradle.tooling.provider.model.internal.IntermediateToolingModelProvider;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the {@link org.gradle.tooling.model.idea.IdeaProject} model in Isolated Projects-compatible way.
 */
@NonNullApi
public class IsolatedProjectsSafeIdeaModelBuilder implements IdeaModelBuilderInternal {

    private final IntermediateToolingModelProvider intermediateToolingModelProvider;
    private final GradleProjectBuilderInternal gradleProjectBuilder;

    public IsolatedProjectsSafeIdeaModelBuilder(IntermediateToolingModelProvider intermediateToolingModelProvider, GradleProjectBuilderInternal gradleProjectBuilder) {
        this.intermediateToolingModelProvider = intermediateToolingModelProvider;
        this.gradleProjectBuilder = gradleProjectBuilder;
    }

    @Override
    public boolean canBuild(String modelName) {
        return modelName.equals("org.gradle.tooling.model.idea.IdeaProject");
    }

    @Override
    public DefaultIdeaProject buildAll(String modelName, Project project) {
        return buildForRoot(project, false);
    }

    @Override
    public DefaultIdeaProject buildForRoot(Project project, boolean offlineDependencyResolution) {
        // TODO: support the case when target is not root project
        Project root = project.getRootProject();
        // TODO: do we need to apply the idea plugin to all included builds? (vanilla builder does it)
        IsolatedIdeaModuleParameter parameter = createParameter(offlineDependencyResolution);
        return buildRoot(root, parameter);
    }

    private DefaultIdeaProject buildRoot(Project rootProject, IsolatedIdeaModuleParameter parameter) {
        // We have to apply the plugin to the root, because users might have done it themselves, and overridden some settings via the extension
        // This means, the plugin has to be made CC and IP compatible, at least for the root
        rootProject.getPluginManager().apply(IdeaPlugin.class);
        IdeaModel ideaModelExt = rootProject.getPlugins().getPlugin(IdeaPlugin.class).getModel();
        IdeaProjectInternal ideaProjectExt = (IdeaProjectInternal) ideaModelExt.getProject();

        List<Project> allProjects = new ArrayList<>(rootProject.getAllprojects());
        List<IsolatedIdeaModuleInternal> allIsolatedIdeaModules = getIsolatedIdeaModules(allProjects, parameter);

        IdeaLanguageLevel languageLevel = resolveRootLanguageLevel(ideaProjectExt, allIsolatedIdeaModules);
        JavaVersion targetBytecodeVersion = resolveRootTargetBytecodeVersion(ideaProjectExt, allIsolatedIdeaModules);

        DefaultIdeaProject out = buildWithoutChildren(ideaProjectExt, languageLevel, targetBytecodeVersion);

        // Important to build GradleProject after the IsolatedIdeaModuleInternal requests,
        // to make sure IdeaPlugin is applied to each project and its tasks are registered
        DefaultGradleProject rootGradleProject = gradleProjectBuilder.buildForRoot(rootProject);

        IdeaModuleBuilder ideaModuleBuilder = new IdeaModuleBuilder(rootGradleProject, languageLevel, targetBytecodeVersion);
        out.setChildren(createIdeaModules(out, ideaModuleBuilder, allProjects, allIsolatedIdeaModules));

        return out;
    }

    private static DefaultIdeaProject buildWithoutChildren(IdeaProjectInternal ideaProjectExt, IdeaLanguageLevel languageLevel, JavaVersion targetBytecodeVersion) {
        return new DefaultIdeaProject()
            .setName(ideaProjectExt.getName())
            .setJdkName(ideaProjectExt.getJdkName())
            .setLanguageLevel(new DefaultIdeaLanguageLevel(languageLevel.getLevel()))
            .setJavaLanguageSettings(new DefaultIdeaJavaLanguageSettings()
                .setSourceLanguageLevel(IdeaModuleBuilderSupport.convertToJavaVersion(languageLevel))
                .setTargetBytecodeVersion(targetBytecodeVersion)
                .setJdk(DefaultInstalledJdk.current()));
    }

    // Simulates computation of the language level property *for the root project* in the IdeaPlugin
    private static IdeaLanguageLevel resolveRootLanguageLevel(IdeaProjectInternal ideaProjectExt, List<IsolatedIdeaModuleInternal> isolatedModules) {
        IdeaLanguageLevel explicitLanguageLevel = ideaProjectExt.getRawLanguageLevel();
        if (explicitLanguageLevel != null) {
            return explicitLanguageLevel;
        }

        JavaVersion maxCompatibility = getMaxCompatibility(isolatedModules, IsolatedIdeaModuleInternal::getJavaSourceCompatibility);
        return new IdeaLanguageLevel(maxCompatibility);
    }

    // Simulates computation of the target bytecode version property *for the root project* in the IdeaPlugin
    private static JavaVersion resolveRootTargetBytecodeVersion(IdeaProjectInternal ideaProjectExt, List<IsolatedIdeaModuleInternal> isolatedModules) {
        JavaVersion explicitTargetBytecodeVersion = ideaProjectExt.getRawTargetBytecodeVersion();
        if (explicitTargetBytecodeVersion != null) {
            return explicitTargetBytecodeVersion;
        }

        return getMaxCompatibility(isolatedModules, IsolatedIdeaModuleInternal::getJavaTargetCompatibility);
    }

    private List<IsolatedIdeaModuleInternal> getIsolatedIdeaModules(List<Project> allProjects, IsolatedIdeaModuleParameter parameter) {
        return intermediateToolingModelProvider
            .getModels(allProjects, IsolatedIdeaModuleInternal.class, parameter);
    }

    private static List<DefaultIdeaModule> createIdeaModules(
        DefaultIdeaProject parent,
        IdeaModuleBuilder ideaModuleBuilder,
        List<Project> projects,
        List<IsolatedIdeaModuleInternal> isolatedIdeaModules
    ) {
        return Streams.zip(projects.stream(), isolatedIdeaModules.stream(), ideaModuleBuilder::buildWithoutParent)
            .map(it -> it.setParent(parent))
            .collect(Collectors.toList());
    }

    private static JavaVersion getMaxCompatibility(List<IsolatedIdeaModuleInternal> isolatedIdeaModules, Function<IsolatedIdeaModuleInternal, JavaVersion> getCompatibilty) {
        return isolatedIdeaModules.stream()
            .map(getCompatibilty)
            .filter(Objects::nonNull)
            .max(JavaVersion::compareTo)
            .orElse(IdeaModuleSupport.FALLBACK_MODULE_JAVA_COMPATIBILITY_VERSION);
    }

    private static IsolatedIdeaModuleParameter createParameter(boolean offlineDependencyResolution) {
        return () -> offlineDependencyResolution;
    }

    @NonNullApi
    private static class IdeaModuleBuilder {

        private final DefaultGradleProject rootGradleProject;
        private final IdeaLanguageLevel ideaProjectLanguageLevel;
        private final JavaVersion ideaProjectTargetBytecodeVersion;

        private IdeaModuleBuilder(
            DefaultGradleProject rootGradleProject,
            IdeaLanguageLevel ideaProjectLanguageLevel,
            JavaVersion ideaProjectTargetBytecodeVersion
        ) {
            this.rootGradleProject = rootGradleProject;
            this.ideaProjectLanguageLevel = ideaProjectLanguageLevel;
            this.ideaProjectTargetBytecodeVersion = ideaProjectTargetBytecodeVersion;
        }

        private DefaultIdeaModule buildWithoutParent(Project project, IsolatedIdeaModuleInternal isolatedIdeaModule) {
            DefaultIdeaModule model = new DefaultIdeaModule()
                .setName(isolatedIdeaModule.getName())
                .setGradleProject(rootGradleProject.findByPath(project.getPath()))
                .setContentRoots(Collections.singletonList(isolatedIdeaModule.getContentRoot()))
                .setJdkName(isolatedIdeaModule.getJdkName())
                .setCompilerOutput(isolatedIdeaModule.getCompilerOutput());

            boolean javaExtensionAvailableOnModule = isolatedIdeaModule.getJavaSourceCompatibility() != null
                || isolatedIdeaModule.getJavaTargetCompatibility() != null;
            if (javaExtensionAvailableOnModule) {
                IdeaLanguageLevel languageLevel = resolveLanguageLevel(isolatedIdeaModule);
                IdeaLanguageLevel moduleLanguageLevelOverride = takeIfDifferent(ideaProjectLanguageLevel, languageLevel);
                JavaVersion targetBytecodeVersion = resolveTargetBytecodeVersion(isolatedIdeaModule);
                JavaVersion moduleTargetBytecodeVersionOverride = takeIfDifferent(ideaProjectTargetBytecodeVersion, targetBytecodeVersion);
                model.setJavaLanguageSettings(new DefaultIdeaJavaLanguageSettings()
                    .setSourceLanguageLevel(IdeaModuleBuilderSupport.convertToJavaVersion(moduleLanguageLevelOverride))
                    .setTargetBytecodeVersion(moduleTargetBytecodeVersionOverride));
            }

            model.setDependencies(isolatedIdeaModule.getDependencies());

            return model;
        }

        private static @Nullable JavaVersion resolveTargetBytecodeVersion(IsolatedIdeaModuleInternal isolatedIdeaModule) {
            JavaVersion targetBytecodeVersionConvention = isolatedIdeaModule.getJavaTargetCompatibility();
            JavaVersion explicitTargetBytecodeVersion = isolatedIdeaModule.getExplicitTargetBytecodeVersion();
            return getPropertyValue(explicitTargetBytecodeVersion, targetBytecodeVersionConvention);
        }

        private static IdeaLanguageLevel resolveLanguageLevel(IsolatedIdeaModuleInternal isolatedIdeaModule) {
            JavaVersion languageLevelConvention = isolatedIdeaModule.getJavaSourceCompatibility();
            IdeaLanguageLevel explicitLanguageLevel = isolatedIdeaModule.getExplicitSourceLanguageLevel();
            return getPropertyValue(explicitLanguageLevel, new IdeaLanguageLevel(languageLevelConvention));
        }

        private static <T> @Nullable T takeIfDifferent(T commonValue, @Nullable T value) {
            return commonValue.equals(value) ? null : value;
        }

        private static <T> @Nullable T getPropertyValue(@Nullable T value, @Nullable T convention) {
            return value != null ? value : convention;
        }
    }

}
