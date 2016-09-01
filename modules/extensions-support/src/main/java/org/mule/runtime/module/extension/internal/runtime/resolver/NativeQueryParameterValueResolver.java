/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.module.extension.internal.runtime.resolver;

import static org.mule.runtime.extension.api.dsql.DsqlParser.isDsqlQuery;
import org.mule.runtime.core.api.MuleEvent;
import org.mule.runtime.core.api.MuleException;
import org.mule.runtime.extension.api.dsql.DsqlParser;
import org.mule.runtime.extension.api.dsql.DsqlQuery;
import org.mule.runtime.extension.api.introspection.dsql.QueryTranslator;

/**
 * {@link ValueResolver} implementation which translates {@link DsqlQuery}s to queries in the Native Query Language.
 * <p>
 * If the query provided is not a {@link DsqlQuery} then is considered a Native Query and returned as it is.
 *
 * @since 4.0
 */
public final class NativeQueryParameterValueResolver implements ValueResolver<String> {

  private static final DsqlParser dsqlParser = DsqlParser.getInstance();

  private final String query;
  private final QueryTranslator translator;

  public NativeQueryParameterValueResolver(String query, QueryTranslator translator) {
    this.query = query;
    this.translator = translator;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String resolve(MuleEvent event) throws MuleException {
    if (!isDsqlQuery(query)) {
      return query;
    }
    DsqlQuery parse = dsqlParser.parse(query);
    return parse.translate(translator);
  }

  /**
   * @return {@code false}
   */
  @Override
  public boolean isDynamic() {
    return false;
  }
}
