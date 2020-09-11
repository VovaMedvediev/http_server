package volodymyr.medvediev.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Properties;

public class HttpServer {

    private static final String CONFIG_FILE = "server.properties";
    private static final String PORT_PARAM = "server.port";
    private static final String NAME_PARAM = "server.name";

    public static void main(String[] args) {
        Properties config;

        InetAddress serverAddress;
        try (InputStream inputStream = ClassLoader.getSystemClassLoader().getResourceAsStream(CONFIG_FILE)) {
            config = getServerConfiguration(System.getProperties(), inputStream);
            String hostname = config.getProperty(NAME_PARAM);
            serverAddress = InetAddress.getByName(hostname);
        } catch (IOException e) {
            System.out.println("Error occurred while reading configuration: " + e.getMessage());
            return;
        }

        int serverPort = Integer.parseInt(config.getProperty(PORT_PARAM));
        try (ServerSocket serverSocket = new ServerSocket(serverPort, 50, serverAddress)) {
            System.out.printf("HttpServer started on http://%s:%d\n", serverAddress.getHostName(), serverPort);
            while (true) {
                HttpRequestHandler requestHandler = new HttpRequestHandler(serverSocket.accept(), config, System.out);

                Thread thread = new Thread(requestHandler);
                thread.start();
            }
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }
    }

    private static Properties getServerConfiguration(Properties userProps, InputStream stream) throws IOException {
        Properties props = new Properties();
        props.load(stream);

        userProps.keySet().forEach(k -> {
            String key = (String) k;
            String value = userProps.getProperty(key);
            if (value != null) {
                props.put(key, value);
            }
        });
        return props;
    }
}
