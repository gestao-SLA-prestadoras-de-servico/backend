package com.gestaosla.backend;

import java.io.IOException;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * RabbitMQ real, com a topologia de infra/rabbitmq/topologia.json importada (outbox-02). Mesma imagem do
 * infra/docker-compose.yml. Um container por JVM de teste, iniciado no primeiro uso.
 */
public final class BrokerTeste {

    public static final String USUARIO = "teste";

    @SuppressWarnings("resource")
    public static final GenericContainer<?> CONTAINER = new GenericContainer<>("rabbitmq:4.1-management-alpine")
            .withEnv("RABBITMQ_DEFAULT_USER", USUARIO)
            .withEnv("RABBITMQ_DEFAULT_PASS", USUARIO)
            .withCopyFileToContainer(MountableFile.forHostPath("infra/rabbitmq/topologia.json"), "/tmp/topologia.json")
            .withExposedPorts(5672, 15672)
            .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

    static {
        CONTAINER.start();
        rabbitmqctl("import_definitions", "/tmp/topologia.json");
    }

    private BrokerTeste() {
    }

    public static int portaAmqp() {
        return CONTAINER.getMappedPort(5672);
    }

    public static String urlGerenciamento() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(15672);
    }

    /** Para ({@code stop_app}) e religa ({@code start_app}) o broker sem trocar a porta; as filas duráveis ficam. */
    public static void rabbitmqctl(String... argumentos) {
        String[] comando = new String[argumentos.length + 1];
        comando[0] = "rabbitmqctl";
        System.arraycopy(argumentos, 0, comando, 1, argumentos.length);
        try {
            ExecResult resultado = CONTAINER.execInContainer(comando);
            if (resultado.getExitCode() != 0) {
                throw new IllegalStateException(String.join(" ", comando) + ": " + resultado.getStderr());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
