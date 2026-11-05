package example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Add the controller, service and handler below your existing application's scan package.
// Поместите контроллер, сервис и обработчик под пакетом сканирования вашего приложения.
@SpringBootApplication
public class ReportApplication {
    public static void main(String[] args) {
        var context = SpringApplication.run(ReportApplication.class, args);
        // Only the opt-in finite verification closes immediately; normal HTTP hosting stays running.
        // Только отдельная конечная проверка закрывается сразу; обычный HTTP-сервис продолжает работу.
        if (context.getEnvironment().getProperty("reports.verify", Boolean.class, false)) context.close();
    }
}
