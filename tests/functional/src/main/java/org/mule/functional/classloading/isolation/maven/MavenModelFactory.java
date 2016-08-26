/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.maven;

import java.io.File;
import java.io.FileReader;
import java.util.Properties;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

/**
 * Creates Maven {@link Model}.
 *
 * @since 4.0
 */
public class MavenModelFactory {

  public static final String BASEDIR_MAVEN_PROPERTY = "basedir";

  /**
   * Creates a {@link Model} by reading the {@code pom.xml} file.
   *
   * @param pomFile to parse and read the model
   * @return {@link Model} representing the Maven project from pom file.
   */
  public static Model createMavenProject(File pomFile) {
    MavenXpp3Reader mavenReader = new MavenXpp3Reader();

    if (pomFile != null && pomFile.exists()) {
      try (FileReader reader = new FileReader(pomFile)) {
        Model model = mavenReader.read(reader);
        model.setPomFile(pomFile);

        Properties properties = model.getProperties();
        properties.setProperty(BASEDIR_MAVEN_PROPERTY, pomFile.getParent());
        Parent parent = model.getParent();

        if (parent != null) {
          File parentPom = new File(pomFile.getParent(), parent.getRelativePath());
          Model parentProj = createMavenProject(parentPom);

          if (parentProj == null) {
            throw new RuntimeException("Unable to load parent project at: " + parentPom.getAbsolutePath());
          }

          properties.putAll(parentProj.getProperties());
        }

        return model;
      } catch (Exception e) {
        throw new RuntimeException("Couldn't get Maven Artifact from pom: " + pomFile);
      }
    }
    throw new IllegalArgumentException("pom file doesn't exits for path: " + pomFile);
  }
}
