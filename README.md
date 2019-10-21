## Serve
A utility to serve files to `localhost`, auto-reloading `index.html` on file change.

**Features:**
* Simple implementation in Java 8 with **NO DEPENDENCIES**: 12kb download.
* Similar Node.js modules pull in hundreds to thousands (!!) of potentially insecure packages.
* Implemented with standard NIO SocketChannels and Buffers; startup is fast.
* Minimal resource usage: < 1mb memory with native WatchService, 3mb otherwise.

### Download
[serve.jar](https://github.com/AugustNagro/serve/raw/master/dist/serve.jar)

### Run It!
Serve is a simple executable jar... run however you'd like. I recommend:

```shell script
java -Xmx3m -XX:+UseSerialGC -jar serve.jar
```

To use port other than `8080`:
```shell script
java -jar serve.jar <port>
```

To create an alias in `.bash_profile`:
```shell script
mv serve.jar ~/serve.jar
echo 'alias serve="java -Xmx3m -XX:+UseSerialGC -jar ~/serve.jar"' >> .bash_profile
```

### Implementation Notes:

* Unfortunately Java's WatchService updates slowly on Mac without registering with the jdk-internal `com.sun.nio.file.SensitivityWatchEventModifier.HIGH` modifier. Hopefully this is fixed since it's a popular issue on SO (https://stackoverflow.com/questions/9588737/is-java-7-watchservice-slow-for-anyone-else).

* How to close AWT EventQueue, which seems to persist after calling Desktop.getDesktop()?
