/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import static java.util.Collections.emptyList;
import org.mule.functional.api.classloading.isolation.MavenMultiModuleArtifactMapping;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link WorkspaceReader} that resolves artifacts using the IDE workspace or Maven multi-module reactor.
 *
 * @since 4.0
 */
public class DefaultWorkspaceReader implements WorkspaceReader {

  public static final String WORKSPACE = "workspace";
  public static final String ZIP = "zip";
  public static final String REDUCED_POM_XML = "dependency-reduced-pom.xml";
  private static final String JAR = "jar";
  private static final String POM = "pom";
  public static final String POM_XML = POM + ".xml";
  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  private final WorkspaceRepository workspaceRepository = new WorkspaceRepository(WORKSPACE);
  private final MavenMultiModuleArtifactMapping mavenMultiModuleArtifactMapping;

  public DefaultWorkspaceReader(MavenMultiModuleArtifactMapping mavenMultiModuleArtifactMapping) {
    this.mavenMultiModuleArtifactMapping = mavenMultiModuleArtifactMapping;
  }

  @Override
  public WorkspaceRepository getRepository() {
    return workspaceRepository;
  }

  @Override
  public File findArtifact(Artifact artifact) {
    if (!artifact.isSnapshot()) {
      return null;
    }
    try {
      String folder = mavenMultiModuleArtifactMapping.getFolderName(artifact.getArtifactId());
      File artifactFile = null;
      if (artifact.getExtension().equals(POM)) {
        if (hasMavenShadePlugin(new File(folder, POM_XML))) {
          File reducedPom = new File(folder, REDUCED_POM_XML);
          if (!reducedPom.exists()) {
            throw new IllegalStateException(artifact + " has in its build configure maven-shade-plugin but the " + REDUCED_POM_XML
                + " is not present. Run the plugin first.");
          }
          artifactFile = reducedPom;
        } else {
          artifactFile = new File(folder, POM_XML);
        }
      } else if (isTestArtifact(artifact)) {
        artifactFile = new File(new File(folder, "target"), "test-classes");
      } else if (artifact.getExtension().equals(JAR) || artifact.getExtension().equals(ZIP)) {
        artifactFile = new File(new File(folder, "target"), "classes");
      }
      if (artifactFile != null && artifactFile.exists()) {
        return artifactFile.getAbsoluteFile();
      }
      if (logger.isTraceEnabled()) {
        //logger.trace("Mapping for artifactId '{}' could be wrong, it is going to be resolved using local repository", artifact);
      }
      return null;
    } catch (IllegalArgumentException e) {
      if (logger.isTraceEnabled()) {
        //logger.trace(
        //             "Couldn't get from workspace the location for artifactId '{}', it is going to be resolved using local repository",
        //             artifact);
      }
      return null;
    }
  }

  /**
   * Determines whether the specified artifact refers to test classes.
   *
   * @param artifact The artifact to check, must not be {@code null}.
   * @return {@code true} if the artifact refers to test classes, {@code false} otherwise.
   */
  private static boolean isTestArtifact(Artifact artifact) {
    return ("test-jar".equals(artifact.getProperty("type", "")))
        || ("jar".equals(artifact.getExtension()) && "tests".equals(artifact.getClassifier()));
  }

  @Override
  public List<String> findVersions(Artifact artifact) {
    return emptyList();
  }

  public boolean hasMavenShadePlugin(File pomFile) {
    MavenXpp3Reader mavenReader = new MavenXpp3Reader();

    if (pomFile != null && pomFile.exists()) {
      FileReader reader = null;

      try {
        reader = new FileReader(pomFile);
        Model model = mavenReader.read(reader);
        model.setPomFile(pomFile);

        if (model.getBuild() != null) {
          for (Plugin plugin : model.getBuild().getPlugins()) {
            if (plugin.getArtifactId().equals("maven-shade-plugin")) {
              return true;
            }
          }
        }

        Parent parent = model.getParent();
        if (parent != null) {
          File parentPom = new File(pomFile.getParent(), parent.getRelativePath());
          return hasMavenShadePlugin(parentPom);
        }
      } catch (Exception e) {
        throw new RuntimeException("Error while reading Maven model for pom file: " + pomFile, e);
      } finally {
        try {
          reader.close();
        } catch (IOException e) {
          // Nothing to do...
        }
      }
    }

    return false;
  }

}
