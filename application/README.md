# graalvm-weld-test

## Setup
 This requires the graalvm community edition which is linux only.  You can probably run it in a container or VM.
 This requires that you install some jars from GraalVM into your maven repository as follows :
```
export JAVA_HOME=YOUR_GRAAL_INSTALL
mvn install:install-file -Dfile=${JAVA_HOME}/jre/lib/svm/builder/svm.jar -DgroupId=com.oracle.svm -DartifactId=svm -Dversion=GraalVM-1.0.0-rc2 -Dpackaging=jar
```

## Building Native Image

The classpath and reflection options probably aren't required

```
mvn install

cd target

native-image -jar ./graalvm-weld-test-1.0-SNAPSHOT.jar  
```

## Interesting Bits

Main2 starts up the application, ServiceLoader is copied from Java's ServiceLoader class and I added logging to figuring things out.  However, the magic that makes serviceLoader work is in the Main2Feature.java file
