/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.compatibility.core.endpoint.outbound;

import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.MuleEvent;
import org.mule.runtime.core.api.MuleException;
import org.mule.runtime.core.api.processor.MessageProcessor;
import org.mule.runtime.core.message.SessionHandler;
import org.mule.runtime.core.util.ObjectUtils;

/**
 * Stores session information on the outbound message.
 *
 * @see SessionHandler
 */
public class OutboundSessionHandlerMessageProcessor implements MessageProcessor {

  private SessionHandler sessionHandler;
  private MuleContext muleContext;

  public OutboundSessionHandlerMessageProcessor(SessionHandler sessionHandler, MuleContext muleContext) {
    this.sessionHandler = sessionHandler;
    this.muleContext = muleContext;
  }

  @Override
  public MuleEvent process(MuleEvent event) throws MuleException {
    sessionHandler.storeSessionInfoToMessage(event.getSession(), event.getMessage(), muleContext);
    return event;
  }

  @Override
  public String toString() {
    return ObjectUtils.toString(this);
  }
}
