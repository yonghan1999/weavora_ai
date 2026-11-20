package app.weavora.zhijie.controlplane.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TcpTestLinkTest {
    @Test void forwardsBytesRejectsOfflineAndRestoresNewConnections() throws Exception {
        try (var server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
                var worker = Executors.newSingleThreadExecutor(); var link = new TcpTestLink(server.getLocalPort())) {
            server.setSoTimeout(5000);
            var upstream = worker.submit(() -> {
                try (Socket first = server.accept()) {
                    first.setSoTimeout(5000);
                    byte[] request = first.getInputStream().readNBytes(5);
                    assertThat(new String(request, StandardCharsets.UTF_8)).isEqualTo("first");
                    first.getOutputStream().write("reply".getBytes(StandardCharsets.UTF_8));
                    assertThat(first.getInputStream().read()).isEqualTo(-1);
                }
                try (Socket second = server.accept()) {
                    second.setSoTimeout(5000);
                    assertThat(second.getInputStream().read()).isEqualTo(42);
                    second.getOutputStream().write(24);
                }
                return true;
            });
            try (var client = new Socket("127.0.0.1", link.port())) {
                client.setSoTimeout(5000);
                client.getOutputStream().write("first".getBytes(StandardCharsets.UTF_8));
                assertThat(new String(client.getInputStream().readNBytes(5), StandardCharsets.UTF_8)).isEqualTo("reply");
                link.disconnect();
                assertThat(client.getInputStream().read()).isEqualTo(-1);
            }
            try (var rejected = new Socket("127.0.0.1", link.port())) {
                rejected.setSoTimeout(5000);
                assertThat(rejected.getInputStream().read()).isEqualTo(-1);
            }
            assertThat(link.rejectedConnections()).isOne();
            link.restore();
            try (var client = new Socket("127.0.0.1", link.port())) {
                client.setSoTimeout(5000);
                client.getOutputStream().write(42);
                assertThat(client.getInputStream().read()).isEqualTo(24);
            }
            assertThat(upstream.get(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
