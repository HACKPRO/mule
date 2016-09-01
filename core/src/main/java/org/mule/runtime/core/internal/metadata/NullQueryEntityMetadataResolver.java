/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.core.internal.metadata;

import static java.util.Collections.emptySet;
import org.mule.metadata.api.model.MetadataType;
import org.mule.runtime.api.connection.ConnectionException;
import org.mule.runtime.api.metadata.MetadataContext;
import org.mule.runtime.api.metadata.MetadataKey;
import org.mule.runtime.api.metadata.MetadataResolvingException;
import org.mule.runtime.api.metadata.resolving.QueryEntityResolver;

import java.util.Set;

/**
 *
 * @since 1.0
 */
public class NullQueryEntityMetadataResolver implements QueryEntityResolver {

  /**
   *
   */
  @Override
  public Set<MetadataKey> getEntityKeys(MetadataContext context)
      throws MetadataResolvingException, ConnectionException {
    return emptySet();
  }

  @Override
  public MetadataType getEntityMetadata(MetadataContext context, String key)
      throws MetadataResolvingException, ConnectionException {
    return null;
  }
}
