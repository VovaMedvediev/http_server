package volodymyr.medvediev.http;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.zip.GZIPOutputStream;

import static volodymyr.medvediev.http.Http.Header.CONNECTION;
import static volodymyr.medvediev.http.Http.Header.CONTENT_ENCODING;
import static volodymyr.medvediev.http.Http.Header.CONTENT_LENGTH;
import static volodymyr.medvediev.http.Http.Header.CONTENT_TYPE;
import static volodymyr.medvediev.http.Http.Header.DATE;
import static volodymyr.medvediev.http.Http.Header.METHOD;
import static volodymyr.medvediev.http.Http.Header.PROTOCOL;
import static volodymyr.medvediev.http.Http.Header.RESOURCE;
import static volodymyr.medvediev.http.Http.Header.SERVER;
import static volodymyr.medvediev.http.Http.Header.UA;

public class HttpRequestHandler implements Runnable {

    static final String ROOT_PARAM = "server.root";
    private static final String WEB_ROOT = "web.root";
    private static final String SERVER_VERSION_PARAM = "server.response.version";

    private static final String INDEX_HTML = "index.html";
    private static final String BAD_REQUEST = "400.html";
    private static final String NOT_FOUND = "404.html";
    private static final String NOT_IMPLEMENTED = "501.html";
    private static final String GZIP = "gzip";
    private static final DateTimeFormatter HTTP_FORMATTER = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z");

    private final Socket client;
    private final Properties config;
    private final File serverRoot;
    private final String webRoot;
    private final PrintStream logger;

    HttpRequestHandler(Socket client, Properties config, PrintStream logger) {
        this.client = client;
        this.config = config;
        serverRoot = new File(config.getProperty(ROOT_PARAM));
        webRoot = config.getProperty(WEB_ROOT);
        this.logger = logger;
    }

    @Override
    public void run() {

        OutputStream dataOut = null;

        try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
             PrintWriter out = new PrintWriter(client.getOutputStream())) {

            Map<String, String> requestHeaders = parseRequestHeaders(in);

            String method = requestHeaders.get(METHOD);
            String resource = requestHeaders.get(RESOURCE);
            String protocol = requestHeaders.get(PROTOCOL);

            String status;
            String resolvedResourcePath;
            File outputFile;

            if (resource.contains("./") || resource.contains("../")) {
                status = Http.Status.BAD_REQUEST;
                outputFile = new File(this.serverRoot, BAD_REQUEST);
            } else if (Http.Method.GET.equals(method)) {
                resolvedResourcePath = resolveResource(resource);
                outputFile = new File(this.webRoot, resolvedResourcePath);

                if (!outputFile.exists()) {
                    status = Http.Status.NOT_FOUND;
                    outputFile = new File(this.serverRoot, NOT_FOUND);
                } else {
                    if (outputFile.isDirectory()) {
                        outputFile = new File(outputFile, INDEX_HTML);
                    }
                    if (outputFile.exists()) {
                        status = Http.Status.OK;
                    } else {
                        status = Http.Status.NOT_FOUND;
                        outputFile = new File(this.serverRoot, NOT_FOUND);
                    }
                }
            } else {
                outputFile = new File(this.serverRoot, NOT_IMPLEMENTED);
                status = Http.Status.NOT_IMPLEMENTED;
            }

            ZonedDateTime now = ZonedDateTime.now(ZoneId.of("GMT"));
            String date = now.format(HTTP_FORMATTER);

            String mimeType = Files.probeContentType(outputFile.toPath());
            byte[] data = readFile(outputFile);

            writeResponseHeaders(out, protocol, status, mimeType, date, data.length, getContentEncoding(requestHeaders));

            dataOut = getDataOutputStream(requestHeaders, client.getOutputStream());
            dataOut.write(data, 0, data.length);
            if (dataOut instanceof GZIPOutputStream) {
                ((GZIPOutputStream) dataOut).finish();
            } else {
                dataOut.flush();
            }

            log(client.getInetAddress(), date, method, status, requestHeaders.getOrDefault(UA, ""), resource);
        } catch (IOException e) {
            System.out.println(e.getMessage());
        } finally {
            if (dataOut != null) {
                try {
                    dataOut.close();
                } catch (IOException ex) {
                    System.out.println(ex.getMessage());
                }
            }
        }
    }

    private void writeResponseHeaders(PrintWriter out, String protocol, String status, String mimeType, String date,
                                      int length, String contentEncoding) {
        out.println(protocol + status);
        out.println(SERVER + config.getProperty(SERVER_VERSION_PARAM));
        out.println(DATE + date);
        if (contentEncoding != null)
            out.println(CONTENT_ENCODING + contentEncoding);
        out.println(CONTENT_TYPE + mimeType + ";charset=\"utf-8\"");
        out.println(CONTENT_LENGTH + length);
        out.println(CONNECTION + "close");
        out.println();
        out.flush();
    }

    private Map<String, String> parseRequestHeaders(BufferedReader in) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String header = in.readLine();
        StringTokenizer tokenizer = new StringTokenizer(header);
        headers.put(METHOD, tokenizer.nextToken().toUpperCase());
        headers.put(RESOURCE, tokenizer.nextToken().toLowerCase());
        headers.put(PROTOCOL, tokenizer.nextToken());
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).toLowerCase(), line.substring(idx + 1).trim());
            }
        }
        return headers;
    }

    private byte[] readFile(File file) throws IOException {
        byte[] res;
        try (FileInputStream fis = new FileInputStream(file)) {
            int length = (int) file.length();
            res = new byte[length];
            fis.read(res, 0, length);
        }
        return res;
    }

    private void log(InetAddress remoteAddress, String date, String request, String status, String ua,
                     String resource) {
        logger.printf("%s [%s] \"%s\"%s %s %s\n", remoteAddress.getHostAddress(), date, request, status, ua, resource);
    }

    private String resolveResource(String requestedPath) {
        Path resolvedPath = FileSystems.getDefault().getPath("");
        Path other = FileSystems.getDefault().getPath(requestedPath);
        for (Path path : other) {
            if (!path.startsWith(".") && !path.startsWith("..")) {
                resolvedPath = resolvedPath.resolve(path);
            }
        }
        if (resolvedPath.startsWith("")) {
            resolvedPath = resolvedPath.resolve(INDEX_HTML);
        }
        return resolvedPath.toString();
    }

    private OutputStream getDataOutputStream(Map<String, String> requestHeaders, OutputStream outputStream)
            throws IOException {
        String acceptedEncoding = requestHeaders.getOrDefault(Http.Header.ACCEPT_ENCODING, "");
        if (acceptedEncoding.contains("gzip")) {
            return new GZIPOutputStream(outputStream);
        }
        return new BufferedOutputStream(outputStream);
    }

    private String getContentEncoding(Map<String, String> requestHeaders) {
        String acceptedEncoding = requestHeaders.getOrDefault(Http.Header.ACCEPT_ENCODING, "");
        return acceptedEncoding.contains(GZIP) ? GZIP : null;
    }
}
