package com.ecm.document.config;

import io.minio.MinioClient;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * MinIO client configuration.
 *
 * ── WHY A CUSTOM OkHttpClient IS REQUIRED ────────────────────────────────────
 *
 * The MinIO Java SDK uses OkHttp internally for all HTTP communication.
 * The default OkHttpClient has these problems in a long-running service:
 *
 * 1. STALE CONNECTION POOL
 *    OkHttp maintains a connection pool (default: 5 connections, 5 min keep-alive).
 *    If MinIO Docker is restarted (e.g., during dev, port remapping, or OOM kill),
 *    the pooled TCP sockets become dead. The next upload attempt reuses a dead socket,
 *    starts writing the multipart body, and gets:
 *      java.net.SocketException: Broken pipe
 *      Suppressed: java.net.SocketException: Connection reset
 *    This is the exact error seen on boarding-pass.pdf (3.7MB) upload failure.
 *
 * 2. NO WRITE TIMEOUT
 *    Default OkHttp has no write timeout. A slow or stuck upload to MinIO blocks
 *    the Tomcat thread indefinitely.
 *
 * 3. NO RETRY ON CONNECTION FAILURE
 *    Default MinioClient wraps a bare OkHttpClient. retryOnConnectionFailure=true
 *    on the OkHttpClient level allows OkHttp to retry idempotent requests (single
 *    PUT for files < 5MB) when the connection fails before any bytes are sent.
 *
 * FIX:
 *   - Shorten connection pool keep-alive to 90s (well under Docker's 4-min TCP idle)
 *   - Reduce max idle connections to 3 (conservative for dev)
 *   - Set connect/read/write timeouts explicitly
 *   - Enable retryOnConnectionFailure so stale-socket retries work automatically
 */
@Configuration
public class MinioConfig {

    @Value("${ecm.minio.endpoint}")
    private String endpoint;

    @Value("${ecm.minio.access-key}")
    private String accessKey;

    @Value("${ecm.minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        OkHttpClient httpClient = new OkHttpClient.Builder()
                // ── Connection pool ─────────────────────────────────────────────
                // Keep-alive 90s: well under Docker bridge network's 4-minute idle
                // timeout. Prevents stale-socket reuse after container restarts.
                .connectionPool(new ConnectionPool(3, 90, TimeUnit.SECONDS))

                // ── Timeouts ────────────────────────────────────────────────────
                // connect: how long to wait for TCP handshake with MinIO
                .connectTimeout(10, TimeUnit.SECONDS)
                // read: how long to wait for MinIO to start sending a response
                .readTimeout(60, TimeUnit.SECONDS)
                // write: how long to allow writing the upload body to MinIO.
                // 5 minutes covers large documents at typical local Docker speeds.
                // In Azure prod, this may need to be longer (network latency to Blob).
                .writeTimeout(5, TimeUnit.MINUTES)

                // ── Retry on stale connection ────────────────────────────────────
                // OkHttp retries the request if the connection fails before any
                // bytes of the response are received AND the request body is
                // "retryable" (i.e., can be re-read from the beginning).
                // For single PUT uploads (< 5MB), OkHttp buffers the body in
                // memory, making it retryable. For multipart (> 5MB), OkHttp
                // cannot guarantee idempotency so it does NOT retry — this is safe.
                .retryOnConnectionFailure(true)

                .build();

        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .httpClient(httpClient)
                .build();
    }
}