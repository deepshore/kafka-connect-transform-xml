/**
 * Copyright © 2017 Jeremy Custenborder (jcustenborder@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jcustenborder.kafka.connect.transform.xml;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.util.Date;

public class FromXmlTest {

  FromXml.Value transform;

  @BeforeEach
  public void before() throws MalformedURLException {
    File file = new File("src/test/resources/com/github/jcustenborder/kafka/connect/transform/xml/books.xsd");
    this.transform = new FromXml.Value();
    this.transform.configure(
        ImmutableMap.of(
          FromXmlConfig.SCHEMA_PATH_CONFIG, file.getAbsoluteFile().toURL().toString(),
          FromXmlConfig.DECIMAL_SCALE_CONFIG, "2")
    );
  }

  @AfterEach
  public void after() {
    this.transform.close();
  }

  @Test
  public void apply() throws IOException {
    final byte[] input = Files.toByteArray(new File("src/test/resources/com/github/jcustenborder/kafka/connect/transform/xml/books.xml"));
    final ConnectRecord inputRecord = new SinkRecord(
        "test",
        1,
        null,
        null,
        org.apache.kafka.connect.data.Schema.BYTES_SCHEMA,
        input,
        new Date().getTime()
    );

    ConnectRecord record = this.transform.apply(inputRecord);

    Schema priceSchema = record.valueSchema().field("book").schema().valueSchema().field("price").schema();
    Struct books = (Struct) record.value();
    Struct firstBook = (Struct) books.getArray("book").get(0);

    assertEquals("org.apache.kafka.connect.data.Decimal", priceSchema.name());
    assertEquals("2", priceSchema.parameters().get("scale"));
    assertEquals(3, ((BigDecimal)firstBook.get("price")).scale());
    assertEquals(new BigDecimal("44.951"), firstBook.get("price"));
  }

}
