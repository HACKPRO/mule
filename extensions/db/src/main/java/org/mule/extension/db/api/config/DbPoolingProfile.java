/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.extension.db.api.config;

import static org.mule.runtime.extension.api.introspection.parameter.ExpressionSupport.NOT_SUPPORTED;
import org.mule.runtime.extension.api.annotation.Alias;
import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.Parameter;
import org.mule.runtime.extension.api.annotation.param.Optional;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Pooling configuration for JDBC Data Sources capable of
 * pooling connections
 *
 * @since 4.0
 */
@Alias("pooling-profile")
public class DbPoolingProfile {

  /**
   * Maximum number of connections a pool maintains at any given time
   */
  @Parameter
  @Optional(defaultValue = "5")
  @Expression(NOT_SUPPORTED)
  private int maxPoolSize = 5;

  /**
   * Minimum number of connections a pool maintains at any given time
   */
  @Parameter
  @Optional(defaultValue = "0")
  @Expression(NOT_SUPPORTED)
  private int minPoolSize = 0;

  /**
   * Determines how many connections at a time to try to acquire when the pool is exhausted
   */
  @Parameter
  @Optional(defaultValue = "1")
  @Expression(NOT_SUPPORTED)
  private int acquireIncrement = 1;

  /**
   * Determines how many statements are cached per pooled connection. Defaults to 0, meaning statement caching is disabled
   */
  @Parameter
  @Optional(defaultValue = "5")
  @Expression(NOT_SUPPORTED)
  private int preparedStatementCacheSize = 5;

  /**
   * The number of milliseconds a client trying to obtain a connection waits for
   * it to be checked-in or acquired when the pool is exhausted. Zero (default) means wait indefinitely
   */
  @Parameter
  @Optional(defaultValue = "30")
  @Expression(NOT_SUPPORTED)
  private int maxWait = 30;

  /**
   * A {@link TimeUnit} which qualifies the {@link #maxWait}.
   */
  @Parameter
  @Optional(defaultValue = "SECONDS")
  @Expression(NOT_SUPPORTED)
  private TimeUnit maxWaitUnit;


  public int getMaxPoolSize() {
    return maxPoolSize;
  }

  public int getMinPoolSize() {
    return minPoolSize;
  }

  public int getAcquireIncrement() {
    return acquireIncrement;
  }

  public int getPreparedStatementCacheSize() {
    return preparedStatementCacheSize;
  }

  public int getMaxWait() {
    return maxWait;
  }

  public TimeUnit getMaxWaitUnit() {
    return maxWaitUnit;
  }

  @Override
  public int hashCode() {
    return Objects.hash(minPoolSize, maxPoolSize, acquireIncrement, preparedStatementCacheSize, maxWaitUnit, maxWait);
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof DbPoolingProfile)) {
      return false;
    }

    DbPoolingProfile that = (DbPoolingProfile) obj;

    return maxPoolSize == that.maxPoolSize &&
        minPoolSize == that.minPoolSize &&
        acquireIncrement == that.acquireIncrement &&
        preparedStatementCacheSize == that.preparedStatementCacheSize &&
        maxWait == that.maxWait &&
        maxWaitUnit == that.maxWaitUnit;
  }
}
