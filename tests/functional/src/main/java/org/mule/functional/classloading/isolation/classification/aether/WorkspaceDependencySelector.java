/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import org.mule.functional.api.classloading.isolation.WorkspaceLocationResolver;

import java.io.File;
import java.net.URL;
import java.util.List;

import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.Dependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO
 */
public class WorkspaceDependencySelector implements DependencySelector {

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());
  private List<URL> classPath;
  private WorkspaceLocationResolver workspaceLocationResolver;
  private int hashCode;

  public WorkspaceDependencySelector(List<URL> classPath, WorkspaceLocationResolver workspaceLocationResolver) {
    this.classPath = classPath;
    this.workspaceLocationResolver = workspaceLocationResolver;
  }

  @Override
  public boolean selectDependency(Dependency dependency) {
    File workspaceRef = workspaceLocationResolver.resolvePath(dependency.getArtifact().getArtifactId());
    if (workspaceRef == null || dependency.getArtifact().getExtension().equals("pom")) {
      return true;
    }
    boolean select = DefaultWorkspaceReader.findClassPathURL(dependency.getArtifact(), workspaceRef, classPath) != null;
    if (!select) {
      if (logger.isDebugEnabled()) {
        logger
            .warn("'{}' dependency found in dependency graph with a workspace reference at '{}' but not found in class path, therefore it will be ignored",
                  dependency.getArtifact(), workspaceRef.getAbsolutePath());
      }
    }
    return select;
  }

  @Override
  public DependencySelector deriveChildSelector(DependencyCollectionContext context) {
    return this;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    } else if (null == obj || !getClass().equals(obj.getClass())) {
      return false;
    }

    WorkspaceDependencySelector that = (WorkspaceDependencySelector) obj;
    return classPath.equals(that.classPath)
        && workspaceLocationResolver.equals(that.workspaceLocationResolver);
  }


  @Override
  public int hashCode() {
    if (hashCode == 0) {
      int hash = getClass().hashCode();
      hash = hash * 31 + classPath.hashCode();
      hash = hash * 31 + workspaceLocationResolver.hashCode();

      hashCode = hash;
    }
    return hashCode;
  }

}
