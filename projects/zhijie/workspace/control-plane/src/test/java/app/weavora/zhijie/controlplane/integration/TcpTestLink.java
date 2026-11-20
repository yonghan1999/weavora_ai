package app.weavora.zhijie.controlplane.integration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TcpTestLink implements AutoCloseable {
    private final ServerSocket listener;
    private final int upstreamPort;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = new HashSet<>();
    private boolean online = true;
    private boolean closed;
    private int rejected;

    public TcpTestLink(int upstreamPort) throws IOException {
        this.upstreamPort = upstreamPort;
        listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        workers.submit(this::accept);
    }

    public int port() { return listener.getLocalPort(); }

    public synchronized int rejectedConnections() { return rejected; }

    public synchronized void disconnect() {
        online = false;
        for (Socket socket : Set.copyOf(sockets)) close(socket);
        sockets.clear();
    }

    public synchronized void restore() { online = true; }

    private void accept() {
        try {
            while (!listener.isClosed()) {
                Socket downstream = listener.accept();
                synchronized (this) {
                    if (!online || closed) {
                        rejected++;
                        close(downstream);
                        continue;
                    }
                    sockets.add(downstream);
                    workers.submit(() -> forward(downstream));
                }
            }
        } catch (IOException failure) {
            if (!listener.isClosed()) throw new IllegalStateException("Local test link accept failed", failure);
        }
    }

    private void forward(Socket downstream) {
        try (var upstream = new Socket(); downstream) {
            synchronized (this) {
                if (!online || closed) return;
                sockets.add(upstream);
            }
            upstream.connect(new InetSocketAddress("127.0.0.1", upstreamPort), 5000);
            workers.submit(() -> copy(downstream, upstream));
            copy(upstream, downstream);
        } catch (IOException ignored) {
        } finally {
            synchronized (this) { sockets.removeIf(Socket::isClosed); }
        }
    }

    private void copy(Socket source, Socket destination) {
        try { source.getInputStream().transferTo(destination.getOutputStream()); }
        catch (IOException ignored) {
        } finally {
            close(source);
            close(destination);
        }
    }

    private void close(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public void close() throws Exception {
        synchronized (this) {
            closed = true;
            disconnect();
            listener.close();
            workers.shutdown();
        }
        if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            throw new IllegalStateException("Local test link workers did not stop");
        }
    }
}
