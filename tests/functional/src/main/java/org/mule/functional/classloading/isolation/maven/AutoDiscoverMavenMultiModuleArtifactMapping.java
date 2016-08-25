/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.maven;

import org.mule.functional.api.classloading.isolation.MavenMultiModuleArtifactMapping;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

/**
 * Created by guillermofernandes on 8/25/16.
 */
public class AutoDiscoverMavenMultiModuleArtifactMapping implements MavenMultiModuleArtifactMapping {

  private Map<String, File> filePathByArtifactId = new HashMap<>();

  public AutoDiscoverMavenMultiModuleArtifactMapping() {
    File userDir = new File(System.getProperty("user.dir"));
    if (!containsMavenProject(userDir)) {
      throw new IllegalArgumentException("Only Maven projects can be discovered to set the artifactId-folder mapping. There is no pom.xml file at user.dir="
          + userDir);
    }

    File currentDir = userDir;
    File lastMavenProjectDir = currentDir;
    while (containsMavenProject(currentDir)) {
      lastMavenProjectDir = currentDir;
      Model model = readMavenPomFile(getPomFile(currentDir));
      filePathByArtifactId.put(model.getArtifactId(), currentDir);
      currentDir = currentDir.getParentFile();
    }

    try {
      Files.walkFileTree(lastMavenProjectDir.toPath(), new MavenDiscovererFileVisitor());
    } catch (IOException e) {
      throw new RuntimeException("Error while discovering Maven projects from path: " + currentDir.toPath());
    }
  }

  private File getPomFile(File currentDir) {
    return new File(currentDir, "pom.xml");
  }

  private class MavenDiscovererFileVisitor implements FileVisitor<Path> {

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
      return getPomFile(dir.toFile()).exists() ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      if (isPomFile(file.toFile())) {
        Model model = readMavenPomFile(file.toFile());
        filePathByArtifactId.put(model.getArtifactId(), file.getParent().toFile());
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      return FileVisitResult.CONTINUE;
    }
  }

  private Model readMavenPomFile(File pomFile) {
    MavenXpp3Reader mavenReader = new MavenXpp3Reader();

    try (FileReader reader = new FileReader(pomFile)) {
      return mavenReader.read(reader);
    } catch (Exception e) {
      throw new RuntimeException("Error while reading Maven model from " + pomFile, e);
    }
  }

  private boolean containsMavenProject(File userDir) {
    return getPomFile(userDir).exists();
  }

  private boolean isPomFile(File file) {
    return file.getName().equalsIgnoreCase("pom.xml");
  }

  @Override
  public String getFolderName(String artifactId) throws IllegalArgumentException {
    if (!filePathByArtifactId.containsKey(artifactId)) {
      throw new IllegalArgumentException("No multi-module mapping for artifactId: " + artifactId);
    }
    return filePathByArtifactId.get(artifactId).getAbsolutePath();
  }

  @Override
  public String getArtifactId(String path) throws IllegalArgumentException {
    throw new UnsupportedOperationException("Not supported by this implementation");
  }
}
