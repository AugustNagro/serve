package com.augustnagro.serve;

import com.sun.nio.file.SensitivityWatchEventModifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.augustnagro.serve.Main.RELOAD_MSG;
import static java.nio.file.StandardWatchEventKinds.*;

public class FileWatcher implements Runnable {

    private final ByteBuffer RELOAD_MSG_BUF = ByteBuffer.wrap(RELOAD_MSG);
    private final ConcurrentLinkedQueue<SocketChannel> webSockets;

    public FileWatcher(ConcurrentLinkedQueue<SocketChannel> webSockets) {
        this.webSockets = webSockets;
    }

    @Override
    public void run() {
        try {
            WatchService ws = FileSystems.getDefault().newWatchService();
            WatchEvent.Kind[] watchKinds = {ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY};
            Paths.get(".").register(ws, watchKinds, SensitivityWatchEventModifier.HIGH);

            while (true) {
                WatchKey key = ws.take();
                if (key.pollEvents().stream().anyMatch(e -> e.kind() != OVERFLOW)) {
                    System.out.println("Files changed, reloading...");

                    SocketChannel s;
                    while ((s = webSockets.poll()) != null) {
                        while (RELOAD_MSG_BUF.hasRemaining()) s.write(RELOAD_MSG_BUF);
                        RELOAD_MSG_BUF.rewind();
                        s.close();
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    System.err.println("Filewatcher has been invalidated");
                    break;
                }
            }

        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
