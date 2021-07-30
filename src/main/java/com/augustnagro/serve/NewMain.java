package com.augustnagro.serve;

import com.sun.nio.file.SensitivityWatchEventModifier;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import static java.nio.file.StandardWatchEventKinds.*;

public class NewMain {

  private static final WatchEvent.Kind[] WATCH_KINDS = {ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY};
  private static final WatchEvent.Modifier[] WATCH_MODIFIERS =
      {SensitivityWatchEventModifier.HIGH};

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
    String baseHref = args.length > 1 ? args[1] : "/";
    Path root = Paths.get(System.getProperty("user.dir"));
    new NewMain().launch(port, baseHref, root);
  }

  public void launch(int port, String baseHref, Path root) throws Exception {
    DevServer devServer = new DevServer(root, baseHref, port);
    new Thread(devServer).start();

    WatchService ws = FileSystems.getDefault().newWatchService();
    registerDirectories(root, ws);

    while (true) {
      WatchKey key = ws.take();
      List<WatchEvent<?>> events = key.pollEvents();
      boolean shouldReload = false;

      for (WatchEvent<?> event : events) {
        WatchEvent.Kind<?> kind = event.kind();

        if (kind == OVERFLOW) {
          System.err.println("OVERFLOW event occurred, this should not happen often.");
          shouldReload = false;
          // ignore remaining events
          break;
        } else {
          if (isTempFile(event)) continue;
          Path p = (Path) event.context();
          if (kind == ENTRY_CREATE && Files.isDirectory(p)) {
            p.register(ws, WATCH_KINDS, WATCH_MODIFIERS);
          }
          shouldReload = true;
        }
      }

      key.reset();

      if (shouldReload) {
        devServer.notifyBuilding();
        devServer.notifyBuildStepComplete();
      }
    }
  }

  private static boolean isTempFile(WatchEvent<?> event) {
    Path p = (Path) event.context();
    return p.getFileName().toString().endsWith("~")
        || p.toAbsolutePath().toString().contains("/target/")
        || p.getFileName().toString().startsWith(".");
  }

  private static void registerDirectories(Path dir, WatchService ws) throws IOException {
    Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        dir.register(ws, WATCH_KINDS, WATCH_MODIFIERS);
        return FileVisitResult.CONTINUE;
      }
    });
    dir.register(ws, WATCH_KINDS, WATCH_MODIFIERS);
  }
}
