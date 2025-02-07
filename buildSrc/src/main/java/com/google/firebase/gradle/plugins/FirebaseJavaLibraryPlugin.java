// Copyright 2019 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.gradle.plugins;

import com.github.sherter.googlejavaformatgradleplugin.GoogleJavaFormatExtension;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.firebase.gradle.plugins.ci.Coverage;
import java.io.File;
import java.nio.file.Paths;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile;

// TODO(vkryachko): extract functionality common across Firebase{,Java}LibraryPlugin plugins.
public class FirebaseJavaLibraryPlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    project.apply(ImmutableMap.of("plugin", "java-library"));
    project.apply(ImmutableMap.of("plugin", "com.github.sherter.google-java-format"));
    project.getExtensions().getByType(GoogleJavaFormatExtension.class).setToolVersion("1.10.0");

    FirebaseLibraryExtension firebaseLibrary =
        project
            .getExtensions()
            .create("firebaseLibrary", FirebaseLibraryExtension.class, project, LibraryType.JAVA);

    // reduce the likelihood of kotlin module files colliding.
    project
        .getTasks()
        .withType(
            KotlinCompile.class,
            kotlin ->
                kotlin
                    .getKotlinOptions()
                    .setFreeCompilerArgs(
                        ImmutableList.of("-module-name", kotlinModuleName(project))));

    setupStaticAnalysis(project, firebaseLibrary);
    project.getTasks().register("kotlindoc");
  }

  private static void setupStaticAnalysis(Project project, FirebaseLibraryExtension library) {
    project.afterEvaluate(
        p ->
            project
                .getConfigurations()
                .all(
                    c -> {
                      if ("annotationProcessor".equals(c.getName())) {
                        for (String checkProject : library.staticAnalysis.errorproneCheckProjects) {
                          project
                              .getDependencies()
                              .add("annotationProcessor", project.project(checkProject));
                        }
                      }
                      if ("lintChecks".equals(c.getName())) {
                        for (String checkProject :
                            library.staticAnalysis.androidLintCheckProjects) {
                          project
                              .getDependencies()
                              .add("lintChecks", project.project(checkProject));
                        }
                      }
                    }));

    setupApiInformationAnalysis(project);

    project.getTasks().register("firebaseLint", task -> task.dependsOn("lint"));
    Coverage.apply(library);
  }

  private static void setupApiInformationAnalysis(Project project) {
    SourceSet mainSourceSet =
        project
            .getConvention()
            .getPlugin(JavaPluginConvention.class)
            .getSourceSets()
            .getByName("main");
    File outputFile =
        project
            .getRootProject()
            .file(
                Paths.get(
                    project.getRootProject().getBuildDir().getPath(),
                    "apiinfo",
                    project.getPath().substring(1).replace(":", "_")));
    File outputApiFile = new File(outputFile.getAbsolutePath() + "_api.txt");

    File apiTxt =
        project.file("api.txt").exists()
            ? project.file("api.txt")
            : project.file(project.getRootDir() + "/empty-api.txt");
    TaskProvider<ApiInformationTask> apiInfo =
        project
            .getTasks()
            .register(
                "apiInformation",
                ApiInformationTask.class,
                task -> {
                  task.getSources()
                      .value(project.provider(() -> mainSourceSet.getJava().getSrcDirs()));
                  task.getApiTxtFile().set(apiTxt);
                  task.getBaselineFile().set(project.file("baseline.txt"));
                  task.getOutputFile().set(outputFile);
                  task.getOutputApiFile().set(outputApiFile);
                  task.getUpdateBaseline().set(project.hasProperty("updateBaseline"));
                });

    TaskProvider<GenerateApiTxtTask> generateApiTxt =
        project
            .getTasks()
            .register(
                "generateApiTxtFile",
                GenerateApiTxtTask.class,
                task -> {
                  task.getSources()
                      .value(project.provider(() -> mainSourceSet.getJava().getSrcDirs()));
                  task.getApiTxtFile().set(project.file("api.txt"));
                  task.getBaselineFile().set(project.file("baseline.txt"));
                  task.getUpdateBaseline().set(project.hasProperty("updateBaseline"));
                });

    TaskProvider<GenerateStubsTask> docStubs =
        project
            .getTasks()
            .register(
                "docStubs",
                GenerateStubsTask.class,
                task ->
                    task.getSources()
                        .value(project.provider(() -> mainSourceSet.getJava().getSrcDirs())));
    project.getTasks().getByName("check").dependsOn(docStubs);

    project.afterEvaluate(
        p -> {
          FileCollection classpath =
              project
                  .getConfigurations()
                  .getByName("runtimeClasspath")
                  .getIncoming()
                  .artifactView(
                      config ->
                          config.attributes(
                              container ->
                                  container.attribute(
                                      Attribute.of("artifactType", String.class), "jar")))
                  .getArtifacts()
                  .getArtifactFiles();

          apiInfo.configure(t -> t.setClassPath(classpath));
          generateApiTxt.configure(t -> t.setClassPath(classpath));
          docStubs.configure(t -> t.setClassPath(classpath));
        });
  }

  private static String kotlinModuleName(Project project) {

    String fullyQualifiedProjectPath = project.getPath().replaceAll(":", "-");

    return project.getRootProject().getName() + fullyQualifiedProjectPath;
  }
}
