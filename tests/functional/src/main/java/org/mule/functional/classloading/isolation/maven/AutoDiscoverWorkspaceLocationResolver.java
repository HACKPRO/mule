/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.maven;

import static java.lang.System.getProperty;
import static java.lang.System.getenv;
import static java.nio.file.FileVisitResult.CONTINUE;
import static java.nio.file.FileVisitResult.SKIP_SUBTREE;
import static java.nio.file.Files.walkFileTree;
import static java.nio.file.Paths.get;
import static org.mule.runtime.core.util.StringMessageUtils.DEFAULT_MESSAGE_WIDTH;
import org.mule.functional.api.classloading.isolation.WorkspaceLocationResolver;
import org.mule.runtime.core.util.StringMessageUtils;

import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.aether.artifact.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers the Maven projects {@link Artifact} from the {@link System#getProperty(String)} {@value #USER_DIR_SYSTEM_PROPERTY}
 * folder and Maven variable {@value #MAVEN_MULTI_MODULE_PROJECT_DIRECTORY} or environment variable {@value #ROOT_PROJECT_ENV_VAR}
 * to define the root project directory.
 * <p/>
 * In order be discovered Maven projects should be defined in a multi module way.
 * <p/>
 * The discovering process checks if {@value #USER_DIR_SYSTEM_PROPERTY} points to a {@value #POM_XML_FILE} first, then it
 * traverses the parent folder hierarchy until it reaches the parent project for the whole workspace based on either
 * {@value #USER_DIR_SYSTEM_PROPERTY} or {@value #ROOT_PROJECT_ENV_VAR}, or it stops at any parent folder that it is not a Maven
 * project (by checking if it has a {@value #POM_XML_FILE}).
 * <p/>
 * Once root parent pom if found, it traverse the file tree to register each Maven project (module) and location. For the
 * rootProjectDirectory if both variables {@value #USER_DIR_SYSTEM_PROPERTY} and {@value #ROOT_PROJECT_ENV_VAR} are set it will
 * take precedence the one from Maven. If no one of them is set most likely it will register only the Maven project located at
 * {@value #USER_DIR_SYSTEM_PROPERTY}, if it is a Maven project, and artifacts will be resolved using Maven repositories.
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
  public static final String MAVEN_MULTI_MODULE_PROJECT_DIRECTORY = "maven.multiModuleProjectDirectory";
  public static final String ROOT_PROJECT_ENV_VAR = "rootProjectDir";
  public static final String WORKSPACE_ENV_VARIABLE = "WORKSPACE";

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private Map<String, File> filePathByArtifactId = new HashMap<>();

  private int hashCode;

  /**
   * Creates an instance of this class.
   *
   * @throws IllegalArgumentException if the {@value #USER_DIR_SYSTEM_PROPERTY} doesn't point to a Maven project.
   */
  public AutoDiscoverWorkspaceLocationResolver() {
    File userDir = new File(getProperty(USER_DIR_SYSTEM_PROPERTY));
    logger.debug("Discovering workspace artifacts locations from System.property['{}']='{}'", USER_DIR_SYSTEM_PROPERTY, userDir);
    if (!containsMavenProject(userDir)) {
      logger.warn("Couldn't find any workspace reference for artifacts due to '{}' is not a Maven project", userDir);
    }

    Path rootProjectDirectory = getRootProjectPath(userDir);
    logger.debug("Defined rootProjectDirectory='{}'", rootProjectDirectory);

    File currentDir = userDir;
    File lastMavenProjectDir = currentDir;
    while (containsMavenProject(currentDir) && !currentDir.toPath().equals(rootProjectDirectory.getParent())) {
      lastMavenProjectDir = currentDir;
      currentDir = currentDir.getParentFile();
    }

    logger.debug("Top folder found, parent pom found at: '{}'", lastMavenProjectDir);
    try {
      walkFileTree(lastMavenProjectDir.toPath(), new MavenDiscovererFileVisitor());
    } catch (IOException e) {
      throw new RuntimeException("Error while discovering Maven projects from path: " + currentDir.toPath());
    }

    logger.debug("Workspace location discover process completed");
    List<String> messages = Lists.newArrayList("Workspace:");
    messages.add(" ");
    messages.addAll(filePathByArtifactId.keySet());
    logger.debug(StringMessageUtils.getBoilerPlate(Lists.newArrayList(messages), '*', DEFAULT_MESSAGE_WIDTH));
  }

  /**
   * Looks for the root project directory using Maven property {@value #MAVEN_MULTI_MODULE_PROJECT_DIRECTORY}, or System
   * environment variable {@value #ROOT_PROJECT_ENV_VAR} or just the userDir.
   *
   * @param userDir {@link File} the userDir where Java is executed.
   * @return {@link Path} to the root directory or null if couldn't be found.
   */
  private Path getRootProjectPath(File userDir) {
    String rootProjectDirectoryProperty = getProperty(MAVEN_MULTI_MODULE_PROJECT_DIRECTORY);
    if (rootProjectDirectoryProperty != null) {
      logger.debug(
                   "Using Maven System.property['{}']='' to find out project root directory for discovering poms",
                   MAVEN_MULTI_MODULE_PROJECT_DIRECTORY, rootProjectDirectoryProperty);
    } else {
      logger.debug(
                   "Checking if System.env['{}'] is set to find out project root directory for discovering poms",
                   ROOT_PROJECT_ENV_VAR);
      rootProjectDirectoryProperty = getenv(ROOT_PROJECT_ENV_VAR);
    }
    // TODO(gfernandes) Just to make it work with Jenkins! Find out why maven multiModuleProjectDir is not populated!
    if (rootProjectDirectoryProperty == null) {
      logger.debug(
                   "Checking if Jenkins System.env['{}'] is set to find out project root directory for discovering poms",
                   WORKSPACE_ENV_VARIABLE);
      rootProjectDirectoryProperty = getenv(WORKSPACE_ENV_VARIABLE);
    }

    if (rootProjectDirectoryProperty == null) {
      logger.warn(
                  "No way to get the 'rootProjectDirectory' using System.property[{}], neither System.env['{}'] so using: {}." +
                      " Meaning that artifacts would be resolved to Maven repository if they are not found on workspace. " +
                      "If running this from IDE set the environment variable to your $PROJECT_DIR$ for IDEA or $workspace_loc on Eclipse.",
                  MAVEN_MULTI_MODULE_PROJECT_DIRECTORY, ROOT_PROJECT_ENV_VAR,
                  userDir);
      rootProjectDirectoryProperty = userDir.getAbsolutePath();
    }
    return get(rootProjectDirectoryProperty);
  }

  /**
   * {@inheritDoc}
   */
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
    logger.trace("Resolved artifactId from workspace at {}={}", artifactId, path);
    filePathByArtifactId.put(artifactId, path.toFile());
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (null == obj || !getClass().equals(obj.getClass())) {
      return false;
    }

    AutoDiscoverWorkspaceLocationResolver that = (AutoDiscoverWorkspaceLocationResolver) obj;
    return filePathByArtifactId.equals(that.filePathByArtifactId);
  }

  @Override
  public int hashCode() {
    if (hashCode == 0) {
      int hash = getClass().hashCode();
      hash = hash * 31 + filePathByArtifactId.hashCode();

      hashCode = hash;
    }
    return hashCode;
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
