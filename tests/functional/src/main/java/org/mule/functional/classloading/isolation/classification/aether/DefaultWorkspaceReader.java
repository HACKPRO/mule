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
import java.util.List;

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
  private static final String JAR = "jar";
  private static final String POM = "pom";
  public static final String POM_XML = POM + ".xml";
  public static final String REDUCED_POM_XML = "dependency-reduced-pom.xml";
  private static final String TEST_JAR = "test-jar";
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
        //TODO find a way to get MavenModel and check if maven-shade-plugin is there to fail if the reduced pom is not generated!
        File reducedPom = new File(folder, REDUCED_POM_XML);
        if (reducedPom.exists()) {
          artifactFile = reducedPom;
        } else {
          artifactFile = new File(folder, POM_XML);
        }
      } else if (artifact.getExtension().equals(JAR) || artifact.getExtension().equals(ZIP)) {
        artifactFile = new File(new File(folder, "target"), "classes");
      } else if (artifact.getExtension().equals(TEST_JAR)) {
        artifactFile = new File(new File(folder, "target"), "test-classes");
      }
      if (artifactFile != null && artifactFile.exists()) {
        return artifactFile.getAbsoluteFile();
      }
      if (logger.isTraceEnabled()) {
        logger.trace("Mapping for artifactId '{}' could be wrong, it is going to be resolved using local repository", artifact);
      }
      return null;
    } catch (IllegalArgumentException e) {
      if (logger.isTraceEnabled()) {
        logger.trace(
            "Couldn't get from workspace the location for artifactId '{}', it is going to be resolved using local repository",
            artifact);
      }
      return null;
    }
  }

  @Override
  public List<String> findVersions(Artifact artifact) {
    return emptyList();
  }

}
