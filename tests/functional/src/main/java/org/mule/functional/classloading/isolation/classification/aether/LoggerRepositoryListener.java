/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.functional.classloading.isolation.classification.aether;

import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositoryListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RepositoryListener} that logs events from repository system.
 *
 * @since 4.0
 */
public class LoggerRepositoryListener extends AbstractRepositoryListener {

  protected final Logger logger = LoggerFactory.getLogger(this.getClass());

  public void artifactDeployed(RepositoryEvent event) {
    logger.trace("Deployed " + event.getArtifact() + " to " + event.getRepository());
  }

  public void artifactDeploying(RepositoryEvent event) {
    logger.trace("Deploying " + event.getArtifact() + " to " + event.getRepository());
  }

  public void artifactDescriptorInvalid(RepositoryEvent event) {
    logger.trace("Invalid artifact descriptor for " + event.getArtifact() + ": "
        + event.getException().getMessage());
  }

  public void artifactDescriptorMissing(RepositoryEvent event) {
    logger.trace("Missing artifact descriptor for " + event.getArtifact());
  }

  public void artifactInstalled(RepositoryEvent event) {
    logger.trace("Installed " + event.getArtifact() + " to " + event.getFile());
  }

  public void artifactInstalling(RepositoryEvent event) {
    logger.trace("Installing " + event.getArtifact() + " to " + event.getFile());
  }

  public void artifactResolved(RepositoryEvent event) {
    logger.trace("Resolved artifact " + event.getArtifact() + " from " + event.getRepository());
  }

  public void artifactDownloading(RepositoryEvent event) {
    logger.trace("Downloading artifact " + event.getArtifact() + " from " + event.getRepository());
  }

  public void artifactDownloaded(RepositoryEvent event) {
    logger.trace("Downloaded artifact " + event.getArtifact() + " from " + event.getRepository());
  }

  public void artifactResolving(RepositoryEvent event) {
    logger.trace("Resolving artifact " + event.getArtifact());
  }

  public void metadataDeployed(RepositoryEvent event) {
    logger.trace("Deployed " + event.getMetadata() + " to " + event.getRepository());
  }

  public void metadataDeploying(RepositoryEvent event) {
    logger.trace("Deploying " + event.getMetadata() + " to " + event.getRepository());
  }

  public void metadataInstalled(RepositoryEvent event) {
    logger.trace("Installed " + event.getMetadata() + " to " + event.getFile());
  }

  public void metadataInstalling(RepositoryEvent event) {
    logger.trace("Installing " + event.getMetadata() + " to " + event.getFile());
  }

  public void metadataInvalid(RepositoryEvent event) {
    logger.trace("Invalid metadata " + event.getMetadata());
  }

  public void metadataResolved(RepositoryEvent event) {
    logger.trace("Resolved metadata " + event.getMetadata() + " from " + event.getRepository());
  }

  public void metadataResolving(RepositoryEvent event) {
    logger.trace("Resolving metadata " + event.getMetadata() + " from " + event.getRepository());
  }
}
