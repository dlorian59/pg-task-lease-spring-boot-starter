package example;

import io.github.alex59.pgtasklease.PgTaskQueue;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

// Finite consumer verification exercises real HTTP -> queue -> handler -> file, not mocked auto-configuration.
// Конечная проверка потребителя проходит HTTP -> очередь -> обработчик -> файл без подмены автоконфигурации.
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "reports.verify", havingValue = "true")
class ReportVerification {
    @Bean
    ApplicationRunner verify(Environment environment, PgTaskQueue queue,
                             @Value("${reports.directory:reports}") String directory) {
        return args -> {
            UUID id = UUID.randomUUID();
            var client = HttpClient.newHttpClient();
            String url = "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + "/reports/" + id;
            var request = HttpRequest.newBuilder(URI.create(url + "?quarter=2026-Q4"))
                    .PUT(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build();
            for (int attempt = 0; attempt < 2; attempt++)
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 202)
                    throw new IllegalStateException("HTTP submission or dedup failed");
            var conflict = HttpRequest.newBuilder(URI.create(url + "?quarter=2026-Q3"))
                    .PUT(HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(5)).build();
            if (client.send(conflict, HttpResponse.BodyHandlers.discarding()).statusCode() != 202)
                throw new IllegalStateException("An existing ID must retain its first submission");
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (true) {
                var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IllegalStateException("Status unavailable");
                if (response.body().equals("DONE")) break;
                if (response.body().equals("DEAD") || System.nanoTime() > deadline) throw new IllegalStateException("Report failed");
                Thread.sleep(50);
            }
            Path file = Path.of(directory).resolve(id + ".csv");
            if (!Files.readString(file).equals("quarter,invoices,total\n2026-Q4,3,450.00\n"))
                throw new IllegalStateException("Unexpected CSV");
            var snapshot = queue.find(id).block(Duration.ofSeconds(5));
            if (snapshot == null || snapshot.attempts() != 1) throw new IllegalStateException("Unexpected attempt count");
            System.out.println("REPORT_HTTP_DONE duplicate=202 changedDuplicate=202 attempts=1 path=" + file);
        };
    }
}
