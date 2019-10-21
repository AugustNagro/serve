package com.augustnagro.serve;

import java.awt.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;

public class Main {
    public static void main(String... args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new Main().startServer(port);
    }

    static final byte[] INIT_MSG   = encodeWSMsg("init");
    static final byte[] RELOAD_MSG = encodeWSMsg("reloadplz");

    private static final String ENCODED_JS = "\n<script>\n" +
            "(function() {\n" +
            "    var websocket = new WebSocket('ws://localhost:8080/_serveWebsocket');\n" +
            "    websocket.onmessage = function(e) {\n" +
            "        if (e.data !== 'init') location.reload();\n" +
            "    };\n" +
            "})();" +
            "</script>\n";

    private static final int     BODY_TAG_LONGEST_PARTIAL_LEN = "<body".length();
    private static final Pattern REQ_PATTERN                  = Pattern.compile("GET (?<req>\\S+)");
    private static final Pattern WS_KEY                       = Pattern.compile("Sec-WebSocket-Key: (?<key>.*)");
    private static final int     BUFFER_SIZE                  = 250000;

    private final ByteBuffer                           buffer        = ByteBuffer.allocateDirect(BUFFER_SIZE);
    // buffer can be UTF-8, so charBuffer (UTF-16) must be 2x size
    private final CharBuffer                           charBuffer    = CharBuffer.allocate(BUFFER_SIZE);
    private final ConcurrentLinkedQueue<SocketChannel> webSockets    = new ConcurrentLinkedQueue<>();
    private final Matcher                              getReqMatcher = REQ_PATTERN.matcher("");
    private final Matcher                              wsKeyMatcher  = WS_KEY.matcher("");
    private final ByteBuffer                           initMsgBuffer = ByteBuffer.wrap(INIT_MSG);
    private final CharsetEncoder                       utf8Encoder   = UTF_8.newEncoder();
    private final CharsetDecoder                       utf8Decoder   = UTF_8.newDecoder();

    private final ByteBuffer encodedJsBuf = UTF_8.encode(ENCODED_JS);

    private void startServer(int port) {
        new Thread(new FileWatcher(webSockets)).start();

        try (ServerSocketChannel ssc = ServerSocketChannel.open()) {
            ssc.bind(new InetSocketAddress(port));

            // open the browser to localhost, if supported.
            // todo: how to close implicit AWT EventLoop? I noticed persistent AWT threads in VisualVM
            // after calling .getDesktop()
            if (Files.exists(Paths.get("index.html"))
                    && Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(URI.create("http://localhost:" + port));

            while (true) {
                SocketChannel sc = ssc.accept();

                // the resource to serve
                Path res;
                // whether to insert our websocket JS
                boolean servingIndexHtml = true;

                // read request bytes into the buffer.
                buffer.clear();
                sc.read(buffer);
                buffer.flip();
                decodeBufToCharBuf();

                // parse the header for get request
                if (!getReqMatcher.reset(charBuffer).find()) {
                    sc.close();
                    System.err.println("Malformed Request.. could not find GET header");
                    continue;
                }

                // the resource requested, ie `/home` or `/css/styles.css`
                String req = getReqMatcher.group("req");

                // If the request is for `/`, serve index.html
                if (req.equals("/")) {
                    res = Paths.get("index.html");

                } else if (req.equals("/_serveWebsocket")) {
                    // we search the header for WebSocket Key,
                    // perform the protocol switch, and send
                    // an init message.
                    if (!wsKeyMatcher.reset(charBuffer).find()) {
                        sc.close();
                        System.err.println("Could not find websocket key");
                        continue;
                    }

                    // Building the WS Upgrade Header
                    String wsKey = wsKeyMatcher.group("key");
                    byte[] digest = (wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8);
                    String resp = "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Sec-WebSocket-Accept: " +
                            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(digest)) +
                            "\r\n\r\n";

                    // send headers + WS init message,
                    encodeBuf(resp);
                    while (buffer.hasRemaining()) sc.write(buffer);
                    while (initMsgBuffer.hasRemaining()) sc.write(initMsgBuffer);
                    initMsgBuffer.rewind();

                    // add SocketChannel to our concurrent queue, so FileWatcher can
                    // send reload message if necessary.
                    webSockets.offer(sc);
                    continue;

                    // serve index.html in a given directory
                    // ie, req = `/page1/`, then serve `page1/index.html`
                } else if (req.endsWith("/")) {
                    res = Paths.get(req.substring(1) + "index.html");

                } else if (!req.contains(".") && Files.exists(Paths.get(req.substring(1) + "/index.html"))) {
                    res = Paths.get(req.substring(1) + "/index.html");

                    // Otherwise, we're requesting resource like `/styles.css`
                } else if (Files.exists((res = Paths.get(req.substring(1))))) {
                    servingIndexHtml = false;

                    // if we can't find resource, it might be a SPA.. so try
                    // to serve index html
                } else if (Files.exists(Paths.get("index.html"))) {
                    System.out.println("Could not find ." + req + ", serving index.html");
                    res = Paths.get("index.html");
                } else {
                    System.err.println("No such file: " + res);
                    encodeBuf("HTTP/1.0 404 Not Found\r\n");
                    while (buffer.hasRemaining()) sc.write(buffer);
                    sc.close();
                    continue;
                }

                // build the response header
                String date = Instant.now().atOffset(ZoneOffset.UTC).format(RFC_1123_DATE_TIME);
                String respHeader = "HTTP/1.0 200 OK\r\n" +
                        "Content-Type: " + Files.probeContentType(res) + "\r\n" +
                        "Date: " + date + "\r\n";
                long size = Files.size(res);

                // if serving an index.html, insert the websocket JS
                if (servingIndexHtml) {
                    // lets try to find the body tag
                    try (FileChannel fc = FileChannel.open(res)) {
                        // the index of the byte after at the end of `<body>`
                        long afterBodyTag = 0;

                        // todo: think about replacing brute force with something
                        // like Rabin-Karp or Boyer-Moore... but since there's no repeats
                        // will this even help us?

                        // The index.html might be bigger than the buffer, so we
                        // load in iterations, taking care of the split case
                        // ie, buffer1 = ...<bo
                        // and buffer2 = dy>...
                        search:
                        while (fc.position() < fc.size()) {
                            buffer.clear();
                            fc.read(buffer);
                            buffer.flip();
                            decodeBufToCharBuf();

                            if (charBuffer.limit() <= BODY_TAG_LONGEST_PARTIAL_LEN) break;
                            final int searchLimit = charBuffer.limit() - BODY_TAG_LONGEST_PARTIAL_LEN;
                            while (charBuffer.position() < searchLimit) {
                                if (charBuffer.get() == '<'
                                        && charBuffer.get() == 'b'
                                        && charBuffer.get() == 'o'
                                        && charBuffer.get() == 'd'
                                        && charBuffer.get() == 'y'
                                        && charBuffer.get() == '>') {
                                    afterBodyTag = fc.position();
                                    break search;
                                }
                            }

                            fc.position(fc.position() - BODY_TAG_LONGEST_PARTIAL_LEN);
                        }

                        // we could not find body tag.. just transfer the file
                        if (afterBodyTag == 0) {
                            System.err.println("Could not find <body> tag in " + res);
                            respHeader += "Content-Length: " + size + "\r\n\r\n";
                            // write header to socketchannel
                            encodeBuf(respHeader);
                            while (buffer.hasRemaining()) sc.write(buffer);
                            transferFile(fc, sc);

                        } else {
                            // add Content-Length header
                            size += encodedJsBuf.limit();
                            respHeader += "Content-Length: " + size + "\r\n\r\n";
                            // write header to socketchannel
                            encodeBuf(respHeader);
                            while (buffer.hasRemaining()) sc.write(buffer);

                            // send index.html up to afterBodyTag
                            long transferred = 0;
                            while (transferred < afterBodyTag)
                                transferred += fc.transferTo(transferred, afterBodyTag - transferred, sc);

                            // send the JS addition
                            while (encodedJsBuf.hasRemaining()) sc.write(encodedJsBuf);
                            encodedJsBuf.rewind();

                            // send remaining part of file
                            transferred = afterBodyTag;
                            long fileSize = fc.size();
                            while (transferred < fileSize)
                                transferred += fc.transferTo(transferred, fileSize - transferred, sc);
                        }
                    }

                    // otherwise, serve resource
                } else {
                    // add Content-Size to resp header, and send it
                    respHeader += "Content-Length: " + size + "\r\n\r\n";
                    encodeBuf(respHeader);
                    while (buffer.hasRemaining()) sc.write(buffer);

                    try (FileChannel fc = FileChannel.open(res)) {
                        transferFile(fc, sc);
                    }
                }

                sc.close();
            }

        } catch (IOException e) {
            System.err.println("Could not listen on port 8080");
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * Encodes the UTF-16 String to UTF-8, placing bytes in buffer,
     * and flips buffer when done.
     */
    private void encodeBuf(String s) {
        buffer.clear();
        utf8Encoder.reset();
        utf8Encoder.encode(CharBuffer.wrap(s), buffer, true);
        utf8Encoder.flush(buffer);
        buffer.flip();
    }

    /**
     * Decodes UTF-8 buffer to UTF-16, putting result in charBuffer,
     * and flips buffer and charBuffer when done.
     */
    private void decodeBufToCharBuf() {
        charBuffer.clear();
        utf8Decoder.reset();
        utf8Decoder.decode(buffer, charBuffer, true);
        utf8Decoder.flush(charBuffer);
        buffer.flip();
        charBuffer.flip();
    }

    /**
     * transfers all data from FileChannel to SocketChannel, throwing
     * exception on failure
     */
    private void transferFile(FileChannel fc, SocketChannel sc) throws IOException {
        long transferred = 0;
        long size = fc.size();
        while (transferred < size)
            transferred += fc.transferTo(transferred, size - transferred, sc);
    }

    /**
     * Lifted from now lost SO answer. Encodes a websocket message.
     */
    public static byte[] encodeWSMsg(String mess) {
        byte[] rawData = mess.getBytes();

        int frameCount = 0;
        byte[] frame = new byte[10];

        frame[0] = (byte) 129;

        if (rawData.length <= 125) {
            frame[1] = (byte) rawData.length;
            frameCount = 2;
        } else if (rawData.length >= 126 && rawData.length <= 65535) {
            frame[1] = (byte) 126;
            int len = rawData.length;
            frame[2] = (byte) ((len >> 8) & (byte) 255);
            frame[3] = (byte) (len & (byte) 255);
            frameCount = 4;
        } else {
            frame[1] = (byte) 127;
            int len = rawData.length;
            frame[2] = (byte) ((len >> 56) & (byte) 255);
            frame[3] = (byte) ((len >> 48) & (byte) 255);
            frame[4] = (byte) ((len >> 40) & (byte) 255);
            frame[5] = (byte) ((len >> 32) & (byte) 255);
            frame[6] = (byte) ((len >> 24) & (byte) 255);
            frame[7] = (byte) ((len >> 16) & (byte) 255);
            frame[8] = (byte) ((len >> 8) & (byte) 255);
            frame[9] = (byte) (len & (byte) 255);
            frameCount = 10;
        }

        int bLength = frameCount + rawData.length;

        byte[] reply = new byte[bLength];

        int bLim = 0;
        for (int i = 0; i < frameCount; i++) {
            reply[bLim] = frame[i];
            bLim++;
        }
        for (int i = 0; i < rawData.length; i++) {
            reply[bLim] = rawData[i];
            bLim++;
        }

        return reply;
    }
}
