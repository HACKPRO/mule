/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.extension.db.integration.select;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mule.extension.db.internal.domain.metadata.SelectMetadataResolver.DUPLICATE_COLUMN_LABEL_ERROR;
import org.mule.extension.db.integration.AbstractDbIntegrationTestCase;
import org.mule.extension.db.integration.TestDbConfig;
import org.mule.extension.db.integration.model.AbstractTestDatabase;
import org.mule.metadata.api.model.ArrayType;
import org.mule.metadata.api.model.MetadataType;
import org.mule.metadata.api.model.ObjectFieldType;
import org.mule.metadata.api.model.ObjectType;
import org.mule.runtime.api.metadata.descriptor.ComponentMetadataDescriptor;
import org.mule.runtime.api.metadata.resolving.FailureCode;
import org.mule.runtime.api.metadata.resolving.MetadataFailure;
import org.mule.runtime.api.metadata.resolving.MetadataResult;
import org.mule.runtime.core.api.registry.RegistrationException;

import java.util.List;
import java.util.Optional;

import org.junit.Test;
import org.junit.runners.Parameterized;

public class SelectMetadataTestCase extends AbstractDbIntegrationTestCase {

  public SelectMetadataTestCase(String dataSourceConfigResource, AbstractTestDatabase testDatabase) {
    super(dataSourceConfigResource, testDatabase);
  }

  @Parameterized.Parameters
  public static List<Object[]> parameters() {
    return TestDbConfig.getResources();
  }

  @Override
  protected String[] getFlowConfigurationResources() {
    return new String[] {"integration/select/pending/select/select-output-metadata-config.xml"};
  }

  @Test
  public void selectAll() throws Exception {
    ObjectType record = getSelectOutputMetadata("select * from PLANET");

    assertThat(record.getFields().size(), equalTo(3));
    assertFieldOfType(record, "ID", testDatabase.getIdFieldOutputMetaDataType());
    assertFieldOfType(record, "POSITION", testDatabase.getPositionFieldOutputMetaDataType());
    assertFieldOfType(record, "NAME", typeBuilder.stringType().build());
  }

  @Test
  public void selectSome() throws Exception {
    ObjectType record = getSelectOutputMetadata("select ID, POSITION from PLANET");

    assertThat(record.getFields().size(), equalTo(2));
    assertFieldOfType(record, "ID", testDatabase.getIdFieldOutputMetaDataType());
    assertFieldOfType(record, "POSITION", testDatabase.getPositionFieldOutputMetaDataType());
  }

  @Test
  public void selectJoin() throws Exception {
    ObjectType record = getSelectOutputMetadata("select NAME, NAME as NAME2 from PLANET");

    assertThat(record.getFields().size(), equalTo(2));
    assertFieldOfType(record, "NAME", typeBuilder.stringType().build());
    assertFieldOfType(record, "NAME2", typeBuilder.stringType().build());
  }

  @Test
  public void selectInvalidJoin() throws Exception {
    MetadataResult<ComponentMetadataDescriptor> metadata =
        getComponentMetadata("selectMetadata", "select NAME, NAME from PLANET");

    assertThat(metadata.isSuccess(), is(false));
    assertThat(metadata.get().getOutputMetadata().isSuccess(), is(false));
    Optional<MetadataFailure> failure = metadata.get().getOutputMetadata().getFailure();
    assertThat(failure.isPresent(), is(true));
    assertThat(failure.get().getFailureCode(), is(FailureCode.INVALID_METADATA_KEY));
    assertThat(failure.get().getMessage(), is(DUPLICATE_COLUMN_LABEL_ERROR));
  }

  private ObjectType getSelectOutputMetadata(String query) throws RegistrationException {
    MetadataResult<ComponentMetadataDescriptor> metadata = getComponentMetadata("selectMetadata", query);

    assertThat(metadata.isSuccess(), is(true));
    assertThat(metadata.get().getOutputMetadata().isSuccess(), is(true));
    assertThat(metadata.get().getOutputMetadata().get().getPayloadMetadata().isSuccess(), is(true));
    ArrayType output = (ArrayType) metadata.get().getOutputMetadata().get().getPayloadMetadata().get().getType();
    return (ObjectType) output.getType();
  }

  private void assertFieldOfType(ObjectType record, String name, MetadataType type) {
    Optional<ObjectFieldType> field = record.getFieldByName(name);
    assertThat(field.isPresent(), is(true));
    assertThat(field.get().getValue(), equalTo(type));
  }
}
