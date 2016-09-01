/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.extension.db.integration.update;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import org.mule.extension.db.api.StatementResult;
import org.mule.extension.db.integration.AbstractDbIntegrationTestCase;
import org.mule.extension.db.integration.TestDbConfig;
import org.mule.extension.db.integration.model.AbstractTestDatabase;
import org.mule.runtime.api.metadata.descriptor.ComponentMetadataDescriptor;
import org.mule.runtime.api.metadata.descriptor.TypeMetadataDescriptor;
import org.mule.runtime.api.metadata.resolving.MetadataResult;

import java.util.List;

import org.junit.Test;
import org.junit.runners.Parameterized;

public class UpdateOutputMetadataTestCase extends AbstractDbIntegrationTestCase {



  public UpdateOutputMetadataTestCase(String dataSourceConfigResource, AbstractTestDatabase testDatabase) {
    super(dataSourceConfigResource, testDatabase);
  }

  @Parameterized.Parameters
  public static List<Object[]> parameters() {
    return TestDbConfig.getResources();
  }

  @Override
  protected String[] getFlowConfigurationResources() {
    return new String[] {"integration/update/pending/update-output-metadata-config.xml"};
  }

  @Test
  public void updateMetadata() throws Exception {
    MetadataResult<ComponentMetadataDescriptor> metadata =
        getComponentMetadata("updateMetadata", "update PLANET set NAME='Mercury' where POSITION=4");

    assertThat(metadata.isSuccess(), is(true));
    assertThat(metadata.get().getOutputMetadata().isSuccess(), is(true));
    MetadataResult<TypeMetadataDescriptor> output = metadata.get().getOutputMetadata().get().getPayloadMetadata();
    assertThat(output.isSuccess(), is(true));
    assertThat(output.get(), is(typeLoader.load(StatementResult.class)));
  }
}
