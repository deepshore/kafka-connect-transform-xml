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
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FromXmlTest {

  FromXml.Value transform;

  @BeforeEach
  void before() throws MalformedURLException {
    File file = new File("src/test/resources/com/github/jcustenborder/kafka/connect/transform/xml/books.xsd");
    this.transform = new FromXml.Value();
    this.transform.configure(
        ImmutableMap.of(
                FromXmlConfig.SCHEMA_PATH_CONFIG, file.getAbsoluteFile().toURL().toString(),
                FromXmlConfig.XPATH_FOR_RECORD_KEY_CONFIG, "concat(descendant::book/author[1]/text(),descendant::book/title[1]/text())"
        )
    );
  }

  @Test
  void apply() throws IOException {
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

    Schema schema = record.valueSchema();
    assertThat(schema.name()).isEqualTo("com.github.jcustenborder.kafka.connect.transform.xml.model.BooksForm");
    assertThat(schema.fields()).extracting(Field::name).containsOnly("book");

    Struct actualRecordStruct = (Struct) record.value();

    List<Object> book = actualRecordStruct.getArray("book");
    assertThat(book).hasSize(2);

    Struct book1 = (Struct) book.get(0);
    assertThat(book1).hasToString("Struct{author=Writer,title=The First Book,genre=Fiction,price=44.95,pub_date=Sun Oct 01 00:00:00 UTC 2000,review=An amazing story of nothing.,id=bk001}");

    Struct book2 = (Struct) book.get(1);
    assertThat(book2).hasToString("Struct{author=Poet,title=The Poet's First Poem,genre=Poem,price=24.95,pub_date=Sun Oct 01 00:00:00 UTC 2000,review=Least poetic poems.,id=bk002}");

    assertThat(record.key()).isEqualTo("WriterThe First Book");

    assertThat(transform.getGeneratedSourceFiles()).hasSize(4);
    assertThat(transform.getGeneratedCompiledFiles()).hasSize(4);

    transform.close();
  }

}
