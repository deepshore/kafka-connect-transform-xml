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

import com.github.jcustenborder.kafka.connect.xml.Connectable;
import com.github.jcustenborder.kafka.connect.xml.KafkaConnectPlugin;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.sun.codemodel.JCodeModel;
import com.sun.tools.xjc.Options;
import com.sun.tools.xjc.api.S2JJAXBModel;
import com.sun.tools.xjc.api.SchemaCompiler;
import com.sun.tools.xjc.api.XJC;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;

import javax.tools.*;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class XSDCompiler implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(XSDCompiler.class);

  final File tempDirectory;
  final URLClassLoader classLoader;
  final FromXmlConfig config;

  public XSDCompiler(FromXmlConfig config) {
    this.config = config;
    try{
      this.tempDirectory = Files.createTempDirectory(config.xjcPackage).toFile();
    } catch (IOException tempE) {
      throw new IllegalStateException(tempE);
    }
    try {
      this.classLoader = new URLClassLoader(
          new URL[]{
              tempDirectory.toURI().toURL()
          },
          Connectable.class.getClassLoader()
      );
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }


  public JAXBContext compileContext() throws IOException {


    List<String> objectFactoryClasses = new ArrayList<>();
    objectFactoryClasses.add(Connectable.class.getName());
    Set<String> packages = new LinkedHashSet<>();
    packages.add(config.xjcPackage);
    String objectFactoryClass = String.format("%s.ObjectFactory", this.config.xjcPackage);
    objectFactoryClasses.add(objectFactoryClass);

    SchemaCompiler schemaCompiler = XJC.createSchemaCompiler();

    Options options = schemaCompiler.getOptions();
    options.activePlugins.add(new KafkaConnectPlugin());
    options.strictCheck = this.config.optionsStrictCheck;

    options.automaticNameConflictResolution = this.config.optionsAutomaticNameConflictResolution;
    schemaCompiler.setDefaultPackageName(this.config.xjcPackage);
    schemaCompiler.setErrorListener(new ConnectErrorListener(log));
    schemaCompiler.setEntityResolver(options.entityResolver);

    for (URL schemaUrl : this.config.schemaUrls) {
      log.info("compileContext() - Generating source for {}", schemaUrl);

      InputSource inputSource = new InputSource();
      inputSource.setSystemId(schemaUrl.toString());
      schemaCompiler.parseSchema(inputSource);
    }

    S2JJAXBModel model = schemaCompiler.bind();

    if (null == model) {
      throw new ConnectException("Schema compiler could not bind schema.");
    }

    JCodeModel jCodeModel = model.generateCode(null, new ConnectErrorListener(log));

    log.trace("compileContext() - Building model to {}", tempDirectory);
    jCodeModel.build(tempDirectory);

    List<File> sourceFiles = getFilesFromTempDirectory();

    if (log.isTraceEnabled()) {
      log.trace("compileContext() - found {} file(s).\n{}",
          sourceFiles.size(),
          Joiner.on('\n').join(sourceFiles)
      );
    }

    final String classPath = System.getProperty("java.class.path");
    List<String> classPathList = new ArrayList<>();
    classPathList.addAll(ImmutableList.copyOf(classPath.split(":")));
    final URL connectableUrl = Connectable.class.getProtectionDomain().getCodeSource().getLocation();
    classPathList.add(connectableUrl.toString());

    List<String> optionList = new ArrayList<>();
    optionList.add("-classpath");
    optionList.add(Joiner.on(':').join(classPathList));

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
    Locale locale = Locale.getDefault();

    try (StandardJavaFileManager fileManager = javaCompiler.getStandardFileManager(diagnostics, locale, null)) {
      Iterable<? extends JavaFileObject> compilationUnit = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
      JavaCompiler.CompilationTask compilerTask = javaCompiler.getTask(
          null,
          fileManager,
          diagnostics,
          optionList,
          null,
          compilationUnit);

      log.info("Compiling...");
      if (Boolean.FALSE.equals(compilerTask.call())) {
        log.error("Exception while compiling source.");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
          if (log.isErrorEnabled()) {
            log.error(
                "Error on line {} in {}\n{}",
                diagnostic.getLineNumber(),
                diagnostic.getSource().toUri(),
                diagnostic.getMessage(locale)
            );
          }
        }
      }
    }


    List<Class<?>> objectFactories = new ArrayList<>();

    for (String s : objectFactoryClasses) {
      try {
        log.info("Loading {}", s);

        objectFactories.add(
            classLoader.loadClass(s)
        );
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException(
            "Exception thrown while loading " + s,
            e
        );
      }
    }
    log.info("Creating JAXBContext");

    try {
      return JAXBContext.newInstance(Joiner.on(':').join(packages), classLoader);
    } catch (JAXBException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() throws IOException {
    // noop
  }

  public List<File> getFilesFromTempDirectory() {

    try (Stream<Path> filepath = Files.walk(getTempDirectory().toPath())) {
      return filepath.filter(path -> new File(path.toUri()).isFile()).map(path -> new File(path.toUri())).collect(Collectors.toList());
    } catch (IOException e) {
      log.warn("error getting tempfiles.", e);
      return Collections.emptyList();
    }
  }

  public File getTempDirectory() {
    return tempDirectory;
  }

}
