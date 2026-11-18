package com.gestaosla.backend;

import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MinIO real, com a mesma imagem do infra/docker-compose.yml (evidencia-01). Um container por JVM de teste, iniciado no
 * primeiro uso; o TestcontainersConfiguration aponta a aplicação para ele.
 */
public final class MinioTeste {

    public static final String USUARIO = "teste";
    public static final String SENHA = "teste-teste";

    @SuppressWarnings("resource")
    public static final MinIOContainer CONTAINER = new MinIOContainer(
            DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z").asCompatibleSubstituteFor("minio/minio"))
            .withUserName(USUARIO)
            .withPassword(SENHA);

    static {
        CONTAINER.start();
    }

    private MinioTeste() {
    }
}
