package com.subhanmalik.ws;

/** Command-line entry point: {@code server} or {@code client}. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            printUsage();
            return;
        }
        switch (args[0]) {
            case "server": {
                int port = intOption(args, "--port", 8080);
                ChatServer server = new ChatServer(port);
                server.start();
                System.out.println("ws-chat server listening on ws://localhost:" + server.getPort());
                System.out.println("Connect with: java -jar ws-chat.jar client --port "
                        + server.getPort());
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        server.stop();
                    } catch (Exception ignored) {
                        // shutting down anyway
                    }
                }));
                // Park the main thread; the accept loop and handlers run on their own.
                Thread.currentThread().join();
                break;
            }
            case "client": {
                String host = stringOption(args, "--host", "localhost");
                int port = intOption(args, "--port", 8080);
                String nick = stringOption(args, "--nick", null);
                ChatClient.run(host, port, nick);
                break;
            }
            default:
                System.err.println("Unknown command: " + args[0]);
                printUsage();
                System.exit(2);
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java -jar ws-chat.jar server [--port 8080]");
        System.out.println("  java -jar ws-chat.jar client [--host localhost] [--port 8080] [--nick NAME]");
        System.out.println();
        System.out.println("Chat commands (typed in the client):");
        System.out.println("  /nick <name>   change display name");
        System.out.println("  /join <room>   move to another room");
        System.out.println("  /rooms         list rooms");
        System.out.println("  /who           list people in your room");
        System.out.println("  /quit          leave");
    }

    private static String stringOption(String[] args, String name, String defaultValue) {
        for (int i = 1; i + 1 < args.length; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static int intOption(String[] args, String name, int defaultValue) {
        String value = stringOption(args, name, null);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid " + name + " value: " + value);
            System.exit(2);
            return defaultValue; // unreachable
        }
    }
}
