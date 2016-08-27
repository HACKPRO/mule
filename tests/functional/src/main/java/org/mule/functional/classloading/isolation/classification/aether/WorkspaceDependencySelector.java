/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
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
  private List<String> workspaceReferences;
  private WorkspaceLocationResolver workspaceLocationResolver;
  private int hashCode;

  public WorkspaceDependencySelector(List<URL> classPath, WorkspaceLocationResolver workspaceLocationResolver) {
    this.workspaceReferences = classPath.stream().filter(url -> new File(url.getFile()).isDirectory()).map(url -> new File(
                                                                                                                           url.getFile())
                                                                                                                               .getParentFile()
                                                                                                                               .getParentFile()
                                                                                                                               .getAbsolutePath())
        .collect(toSet()).stream().collect(toList());
    this.workspaceLocationResolver = workspaceLocationResolver;
  }

  @Override
  public boolean selectDependency(Dependency dependency) {
    File workspaceRef = workspaceLocationResolver.resolvePath(dependency.getArtifact().getArtifactId());
    if (workspaceRef == null || dependency.getArtifact().getExtension().equals("pom")) {
      return true;
    }
    boolean select = workspaceReferences.contains(workspaceRef.getAbsolutePath());
    if (!select) {
      if (logger.isDebugEnabled()) {
        logger.warn("'{}' dependency not found in classPath therefore will be ignored", dependency.getArtifact());
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
    return workspaceReferences.equals(that.workspaceReferences)
        && workspaceLocationResolver.equals(that.workspaceLocationResolver);
  }


  @Override
  public int hashCode() {
    if (hashCode == 0) {
      int hash = getClass().hashCode();
      hash = hash * 31 + workspaceReferences.hashCode();
      hash = hash * 31 + workspaceLocationResolver.hashCode();

      hashCode = hash;
    }
    return hashCode;
  }

}
