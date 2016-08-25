/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.maven;

import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.Files.walkFileTree;
import org.mule.functional.api.classloading.isolation.WorkspaceLocationResolver;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.aether.artifact.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the Maven projects {@link Artifact} based on the {@link System#getProperty(String)}
 * {@value #USER_DIR_SYSTEM_PROPERTY}.
 * <p/>
 * The discovering process checks if the {@value #USER_DIR_SYSTEM_PROPERTY} points to a {@value #POM_XML_FILE} first, then it goes
 * to the parent folder to check if there a Maven project there too (by checking if it has a {@value #POM_XML_FILE}) until it
 * reaches the parent pom for the whole workspace. Once found it, it traverse the whole file tree to register each artifact and
 * its location.
 * <p/>
 * Be aware that it is not supported to have Maven multi-module projects in your workspace (IDE or Maven build session) without a
 * parent pom to group them. If that's the case you will end up with resolutions to artifacts to the local repository instead of
 * using them from its compiled files.
 *
 * @since 4.0
 */
public class AutoDiscoverWorkspaceLocationResolver implements WorkspaceLocationResolver {

  public static final String POM_XML_FILE = "pom.xml";
  public static final String USER_DIR_SYSTEM_PROPERTY = "user.dir";

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private Map<String, File> filePathByArtifactId = new HashMap<>();

  /**
   * Creates an instance of this class.
   *
   * @throws IllegalArgumentException if the {@value #USER_DIR_SYSTEM_PROPERTY}
   */
  public AutoDiscoverWorkspaceLocationResolver() {
    File userDir = new File(System.getProperty(USER_DIR_SYSTEM_PROPERTY));
    logger.debug("Discovering workspace artifacts locations from {}='{}'", USER_DIR_SYSTEM_PROPERTY, userDir);
    if (!containsMavenProject(userDir)) {
      throw new IllegalArgumentException("Only Maven projects can be discovered to set the artifactId-folder mapping. There is no pom.xml file at user.dir="
          + userDir);
    }

    File currentDir = userDir;
    File lastMavenProjectDir = currentDir;
    while (containsMavenProject(currentDir)) {
      lastMavenProjectDir = currentDir;
      currentDir = currentDir.getParentFile();
    }

    logger.debug("Top folder, parent pom found at: '{}'", lastMavenProjectDir);
    try {
      walkFileTree(lastMavenProjectDir.toPath(), new MavenDiscovererFileVisitor());
    } catch (IOException e) {
      throw new RuntimeException("Error while discovering Maven projects from path: " + currentDir.toPath());
    }
  }

  /**
   * Resolves the {@link File} from the discovered artifacts from the file system workspace.
   *
   * @param artifact to resolve its {@link File} from the workspace
   * @return {@link File} to the artifact of null if not found
   */
  //@Override
  //public File resolvePath(Artifact artifact) {
  //  return filePathByArtifactId.get(artifact.getArtifactId());
  //}
  @Override
  public File resolvePath(String artifactId) {
    return filePathByArtifactId.get(artifactId);
  }

  /**
   * Reads the Maven pom file to get build the {@link Model}.
   *
   * @param pomFile to be read
   * @return {@link Model} represeting the Maven project
   */
  private Model readMavenPomFile(File pomFile) {
    MavenXpp3Reader mavenReader = new MavenXpp3Reader();

    try (FileReader reader = new FileReader(pomFile)) {
      return mavenReader.read(reader);
    } catch (Exception e) {
      throw new RuntimeException("Error while reading Maven model from " + pomFile, e);
    }
  }

  /**
   * Creates a {@link File} for the {@value #POM_XML_FILE} in the given directory
   *
   * @param currentDir to create a {@value #POM_XML_FILE}
   * @return {@link File} to the {@value #POM_XML_FILE} in the give directory
   */
  private File getPomFile(File currentDir) {
    return new File(currentDir, POM_XML_FILE);
  }

  /**
   * @param dir {@link File} directory to check if it has a {@value #POM_XML_FILE}
   * @return true if the directory contains a {@value #POM_XML_FILE}
   */
  private boolean containsMavenProject(File dir) {
    return dir.isDirectory() && getPomFile(dir).exists();
  }

  /**
   * @param file {@link File} to check if it a {@value #POM_XML_FILE}
   * @return true if the file is a {@value #POM_XML_FILE}
   */
  private boolean isPomFile(File file) {
    return file.getName().equalsIgnoreCase(POM_XML_FILE);
  }

  /**
   * Adds the resolved artifact with its path.
   *
   * @param artifactId the Maven artifactId found in workspace
   * @param path the {@link Path} location to the artifactId
   */
  private void resolvedArtifact(String artifactId, Path path) {
    logger.trace("Resolved artifactId from workspace {}={}", artifactId, path);
    filePathByArtifactId.put(artifactId, path.toFile());
  }

  /**
   * Looks for directories that contain a {@value #POM_XML_FILE} file so it will be added to the resolved artifacts locations.
   */
  private class MavenDiscovererFileVisitor implements FileVisitor<Path> {

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
      return getPomFile(dir.toFile()).exists() ? CONTINUE : SKIP_SUBTREE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
      if (isPomFile(file.toFile())) {
        Model model = readMavenPomFile(file.toFile());
        resolvedArtifact(model.getArtifactId(), file.getParent());
      }
      return CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
      return CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
      return CONTINUE;
    }
  }


}
