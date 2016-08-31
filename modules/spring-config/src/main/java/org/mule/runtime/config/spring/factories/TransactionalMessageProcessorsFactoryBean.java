/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.spring.factories;

import static java.util.ServiceLoader.load;
import org.mule.runtime.core.AbstractAnnotatedObject;
import org.mule.runtime.core.api.MuleContext;
import org.mule.runtime.core.api.context.MuleContextAware;
import org.mule.runtime.core.api.exception.MessagingExceptionHandler;
import org.mule.runtime.core.api.exception.MessagingExceptionHandlerAware;
import org.mule.runtime.core.api.processor.MessageProcessor;
import org.mule.runtime.core.api.processor.MessageProcessorBuilder;
import org.mule.runtime.core.api.transaction.TransactionFactory;
import org.mule.runtime.core.api.transaction.TransactionTypeFactory;
import org.mule.runtime.core.processor.DelegateTransactionFactory;
import org.mule.runtime.core.processor.TransactionalInterceptingMessageProcessor;
import org.mule.runtime.core.processor.chain.DefaultMessageProcessorChainBuilder;
import org.mule.runtime.core.transaction.MuleTransactionConfig;
import org.mule.runtime.core.transaction.TransactionType;

import java.util.Iterator;
import java.util.List;

import org.springframework.beans.factory.FactoryBean;

public class TransactionalMessageProcessorsFactoryBean extends AbstractAnnotatedObject implements FactoryBean, MuleContextAware {

  protected List messageProcessors;
  protected MessagingExceptionHandler exceptionListener;
  protected String transactionalAction;
  private TransactionType transactionType;
  private MuleContext muleContext;

  @Override
  public Class getObjectType() {
    return TransactionalInterceptingMessageProcessor.class;
  }

  public void setMessageProcessors(List messageProcessors) {
    this.messageProcessors = messageProcessors;
  }

  @Override
  public Object getObject() throws Exception {
    DefaultMessageProcessorChainBuilder builder = new DefaultMessageProcessorChainBuilder(muleContext);
    builder.setName("'transaction' child processor chain");
    TransactionalInterceptingMessageProcessor txProcessor = new TransactionalInterceptingMessageProcessor();
    txProcessor.setAnnotations(getAnnotations());
    txProcessor.setExceptionListener(this.exceptionListener);
    txProcessor.setTransactionConfig(createTransactionConfig(this.transactionalAction, this.transactionType));
    builder.chain(txProcessor);
    for (Object processor : messageProcessors) {
      if (processor instanceof MessageProcessor) {
        builder.chain((MessageProcessor) processor);
      } else if (processor instanceof MessageProcessorBuilder) {
        builder.chain((MessageProcessorBuilder) processor);
      } else {
        throw new IllegalArgumentException("MessageProcessorBuilder should only have MessageProcessor's or MessageProcessorBuilder's configured");
      }
      if (processor instanceof MessagingExceptionHandlerAware) {
        ((MessagingExceptionHandlerAware) processor).setMessagingExceptionHandler(exceptionListener);
      }
    }
    return builder.build();
  }

  protected TransactionFactory getTransactionFactory() {
    return new DelegateTransactionFactory();
  }

  protected MuleTransactionConfig createTransactionConfig(String action, TransactionType type) {
    MuleTransactionConfig transactionConfig = new MuleTransactionConfig();
    transactionConfig.setActionAsString(action);
    transactionConfig.setFactory(lookUpTransactionFactory(type));
    return transactionConfig;
  }

  private TransactionFactory lookUpTransactionFactory(TransactionType type) {
    Iterator<TransactionTypeFactory> factories = load(TransactionTypeFactory.class).iterator();
    while (factories.hasNext()) {
      TransactionTypeFactory possibleFactory = factories.next();
      if (type.equals(possibleFactory.getType())) {
        return possibleFactory;
      }
    }
    throw new IllegalArgumentException(String.format("No factory available for transaction type %s", type));
  }

  @Override
  public boolean isSingleton() {
    return false;
  }

  public void setExceptionListener(MessagingExceptionHandler exceptionListener) {
    this.exceptionListener = exceptionListener;
  }

  public void setTransactionalAction(String action) {
    this.transactionalAction = action;
  }

  @Override
  public void setMuleContext(MuleContext context) {
    this.muleContext = context;
  }

  public void setTransactionType(TransactionType transactionType) {
    this.transactionType = transactionType;
  }
}
