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

import com.github.jcustenborder.kafka.connect.utils.config.Description;
import com.github.jcustenborder.kafka.connect.utils.config.DocumentationTip;
import com.github.jcustenborder.kafka.connect.utils.config.Title;
import com.github.jcustenborder.kafka.connect.utils.transformation.BaseKeyValueTransformation;
import com.github.jcustenborder.kafka.connect.xml.Connectable;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.errors.DataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.File;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Title("FromXML")
@Description("This transformation is used to read XML data stored as bytes or a string and convert " +
    "the XML to a structure that is strongly typed in connect. This allows data to be converted from XML " +
    "and stored as AVRO in a topic for example. ")
@DocumentationTip("XML schemas can be much more complex that what can be expressed in a Kafka " +
    "Connect struct. Elements that can be expressed as an anyType or something similar cannot easily " +
    "be used to infer type information.")
public abstract class FromXml<R extends ConnectRecord<R>> extends BaseKeyValueTransformation<R> {
  private static final Logger log = LoggerFactory.getLogger(FromXml.class);
  FromXmlConfig config;
  JAXBContext context;
  Unmarshaller unmarshaller;
  Marshaller marshaller;
  XSDCompiler compiler;
  String evaluatedKey;

  private List<File> generatedSourceFiles;

  private List<File> generatedCompiledFiles;

  protected FromXml(boolean isKey) {
    super(isKey);
  }

  @Override
  public ConfigDef config() {
    return FromXmlConfig.config();
  }

  @Override
  public void close() {
    try {
      this.compiler.close();
    } catch (IOException e) {
      log.error("Exception thrown", e);
    }
  }

  @Override
  protected SchemaAndValue processString(R processingRecord, org.apache.kafka.connect.data.Schema inputSchema, String input) {
    try (Reader reader = new StringReader(input)) {
      Object element = this.unmarshaller.unmarshal(reader);
      return schemaAndValue(element);
    } catch (IOException | JAXBException e) {
      throw new DataException("Exception thrown while processing xml", e);
    }
  }

  @Override
  protected SchemaAndValue processBytes(R processingRecord, org.apache.kafka.connect.data.Schema inputSchema, byte[] input) {
    try (InputStream inputStream = new ByteArrayInputStream(input)) {
      try (Reader reader = new InputStreamReader(inputStream)) {
        Object element = this.unmarshaller.unmarshal(reader);
        return schemaAndValue(element);
      }
    } catch (IOException | JAXBException e) {
      throw new DataException("Exception thrown while processing xml", e);
    }
  }

  private void extractKeyByXpath(Object element) {
    if (null != config.xpathForRecordKey) {
      try {
        DocumentBuilderFactory domFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder domBuilder = domFactory.newDocumentBuilder();
        Document doc = domBuilder.newDocument();
        this.marshaller.marshal(element, doc);

        XPathFactory factory = XPathFactory.newInstance();
        XPath xpath = factory.newXPath();

        this.evaluatedKey = (String) xpath.evaluate(
                config.xpathForRecordKey,
                doc, XPathConstants.STRING);

      } catch (Exception e) {
        log.error("Error while extracting key via xpath", e);
      }
    }
  }

  private SchemaAndValue schemaAndValue(Object element) {
    final Struct struct;
    this.evaluatedKey = null;
    this.extractKeyByXpath(element);

    if (element instanceof Connectable) {
      Connectable connectable = (Connectable) element;
      struct = connectable.toStruct();
    } else if (element instanceof JAXBElement) {
      JAXBElement jaxbElement = (JAXBElement) element;

      if (jaxbElement.getValue() instanceof Connectable) {
        Connectable connectable = (Connectable) jaxbElement.getValue();
        struct = connectable.toStruct();
      } else {
        throw new DataException(
            String.format(
                "%s does not implement Connectable",
                jaxbElement.getValue().getClass()
            )
        );
      }
    } else {
      throw new DataException(
          String.format("%s is not a supported type", element.getClass())
      );
    }
    return new SchemaAndValue(struct.schema(), struct);
  }

  @Override
  public void configure(Map<String, ?> settings) {
    this.config = new FromXmlConfig(settings);
    this.compiler = new XSDCompiler(this.config);

    try {
      this.context = compiler.compileContext();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }

    this.generatedSourceFiles = this.compiler.getFilesFromTempDirectory()
            .stream()
            .filter(file -> file.getName().matches("^.*\\.java"))
            .collect(Collectors.toList());

    this.generatedCompiledFiles = this.compiler.getFilesFromTempDirectory()
            .stream()
            .filter(file -> file.getName().matches("^.*\\.class"))
            .collect(Collectors.toList());

    try {
      this.unmarshaller = context.createUnmarshaller();
      this.marshaller = context.createMarshaller();
    } catch (JAXBException e) {
      throw new IllegalStateException(e);
    }
  }

  public List<File> getGeneratedSourceFiles() {
    return generatedSourceFiles;
  }

  public List<File> getGeneratedCompiledFiles() {
    return generatedCompiledFiles;
  }


  public static class Key<R extends ConnectRecord<R>> extends FromXml<R> {
    public Key() {
      super(true);
    }

    @Override
    public R apply(R r) {
      final SchemaAndValue transformed = process(r, new SchemaAndValue(r.keySchema(), r.key()));

      return r.newRecord(
          r.topic(),
          r.kafkaPartition(),
          transformed.schema(),
          transformed.value(),
          r.valueSchema(),
          r.value(),
          r.timestamp()
      );
    }
  }

  public static class Value<R extends ConnectRecord<R>> extends FromXml<R> {
    public Value() {
      super(false);
    }

    @Override
    public R apply(R r) {
      final SchemaAndValue transformed = process(r, new SchemaAndValue(r.valueSchema(), r.value()));


      return r.newRecord(
          r.topic(),
          r.kafkaPartition(),
          Schema.STRING_SCHEMA,
          this.evaluatedKey,
          transformed.schema(),
          transformed.value(),
          r.timestamp()
      );
    }
  }
}
