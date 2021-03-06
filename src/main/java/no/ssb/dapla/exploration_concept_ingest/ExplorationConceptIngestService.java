package no.ssb.dapla.exploration_concept_ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.huxhorn.sulky.ulid.ULID;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.helidon.common.http.Http;
import io.helidon.common.http.MediaType;
import io.helidon.config.Config;
import io.helidon.media.common.DefaultMediaSupport;
import io.helidon.media.jackson.JacksonSupport;
import io.helidon.webclient.WebClient;
import io.helidon.webclient.WebClientRequestBuilder;
import io.helidon.webclient.WebClientResponse;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import no.ssb.rawdata.api.RawdataClient;
import no.ssb.rawdata.api.RawdataClientInitializer;
import no.ssb.rawdata.api.RawdataConsumer;
import no.ssb.rawdata.api.RawdataMessage;
import no.ssb.service.provider.api.ProviderConfigurator;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Optional.ofNullable;

public class ExplorationConceptIngestService implements Service {

    private static final Logger LOG = LoggerFactory.getLogger(ExplorationConceptIngestService.class);

    private final ObjectMapper msgPackMapper = new ObjectMapper(new MessagePackFactory());
    private final Config config;

    private final AtomicBoolean waitLoopAllowed = new AtomicBoolean(false);
    private final Map<Class<?>, Object> instanceByType = new ConcurrentHashMap<>();

    ExplorationConceptIngestService(Config config) {
        this.config = config;
    }

    @Override
    public void update(Routing.Rules rules) {
        rules
                .get("/trigger", this::getRevisionHandler)
                .put("/trigger", this::putRevisionHandler)
                .delete("/trigger", this::deleteRevisionHandler)
        ;
    }

    void getRevisionHandler(ServerRequest request, ServerResponse response) {
        Pipe pipe = (Pipe) instanceByType.get(Pipe.class);
        if (pipe != null) {
            response.headers().contentType(MediaType.APPLICATION_JSON);
            response.status(200).send(msgPackMapper.createObjectNode()
                    .put("status", "RUNNING")
                    .put("startedTime", pipe.startedAt.format(DateTimeFormatter.ISO_INSTANT))
                    .toPrettyString());
        } else {
            response.headers().contentType(MediaType.APPLICATION_JSON);
            response.status(200).send(msgPackMapper.createObjectNode()
                    .put("status", "STOPPED")
                    .toPrettyString());
        }
    }

    void putRevisionHandler(ServerRequest request, ServerResponse response) {
        triggerStart();

        response.headers().contentType(MediaType.APPLICATION_JSON);
        response.status(200).send("[]");
    }

    void deleteRevisionHandler(ServerRequest request, ServerResponse response) {
        triggerStop();
        response.status(200).send();
    }

    public void triggerStop() {
        waitLoopAllowed.set(false);
    }

    public void triggerStart() {
        instanceByType.computeIfAbsent(RawdataClient.class, k -> {
            String provider = config.get("pipe.source.provider.name").asString().get();
            Map<String, String> providerConfig = config.get("pipe.source.provider.config").detach().asMap().get();
            return ProviderConfigurator.configure(providerConfig, provider, RawdataClientInitializer.class);
        });

        instanceByType.computeIfAbsent(WebClient.class, k -> {
            Config targetConfig = this.config.get("pipe.target");
            String scheme = targetConfig.get("scheme").asString().get();
            String host = targetConfig.get("host").asString().get();
            int port = targetConfig.get("port").asInt().get();
            URI ldsBaseUri;
            try {
                ldsBaseUri = new URI(scheme, null, host, port, null, null, null);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }

            return WebClient.builder()
                    .addMediaSupport(DefaultMediaSupport.create())
                    .addMediaSupport(JacksonSupport.create())
                    .baseUri(ldsBaseUri)
                    .build();
        });

        Pipe pipe = (Pipe) instanceByType.computeIfAbsent(Pipe.class, k -> new Pipe());

        instanceByType.computeIfAbsent(Thread.class, k -> {
            Thread thread = new Thread(pipe);
            thread.start();
            return thread;
        });

        waitLoopAllowed.set(true);
    }

    Optional<String> getLatestSourceIdFromTarget() {
        Config targetConfig = this.config.get("pipe.target");
        String source = targetConfig.get("source").asString().get();
        WebClient webClient = (WebClient) instanceByType.get(WebClient.class);
        WebClientRequestBuilder builder = webClient.get();
        builder.headers().add("Origin", "localhost");
        builder.path("source/" + source);

        WebClientResponse response = builder
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .submit()
                .toCompletableFuture()
                .join();

        if (!Http.ResponseStatus.Family.SUCCESSFUL.equals(response.status().family())) {
            throw new RuntimeException("Got http status code " + response.status() + " with reason: " + response.status().reasonPhrase());
        }

        JsonNode body = response.content().as(JsonNode.class).toCompletableFuture().join();
        return ofNullable(body.get("lastSourceId").textValue());
    }

    void sendMessageToTarget(RawdataMessage message) {
        try {
            JsonNode meta = msgPackMapper.readTree(message.get("meta"));

            String method = meta.get("method").textValue();
            String sourceNamespace = meta.get("namespace").textValue();
            String entity = meta.get("entity").textValue();
            String id = meta.get("id").textValue();
            String versionStr = meta.get("version").textValue();

            String namespace = config.get("pipe.target.namespace").asString().get();
            String source = config.get("pipe.target.source").asString().get();
            String sourceId = message.ulid().toString();

            WebClient webClient = (WebClient) instanceByType.get(WebClient.class);

            String path = String.format("/%s/%s/%s", namespace, entity, id);

            // retry at backoff intervals for about 3 minutes before giving up
            Retry retry = Retry.of("write-message-to-lds", RetryConfig.custom()
                    .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.of(5, ChronoUnit.SECONDS), 2, Duration.of(30, ChronoUnit.SECONDS)))
                    .maxAttempts(8)
                    .failAfterMaxAttempts(true)
                    .build());

            if ("PUT".equals(method)) {
                JsonNode data = msgPackMapper.readTree(message.get("data"));
                WebClientResponse response = Retry.decorateSupplier(retry, () -> {
                    WebClientRequestBuilder builder = webClient.put();
                    builder.headers().add("Origin", "localhost");
                    builder.path(path)
                            .queryParam("timestamp", versionStr)
                            .queryParam("source", source)
                            .queryParam("sourceId", sourceId);
                    return builder.submit(data)
                            .toCompletableFuture()
                            .join();
                }).get();
                if (!Http.ResponseStatus.Family.SUCCESSFUL.equals(response.status().family())) {
                    LOG.warn("Unsuccessful HTTP response while attempting to perform: PUT {}?timestamp={}&source={}&sourceId={}   DATA: {}",
                            path,
                            URLEncoder.encode(versionStr, StandardCharsets.UTF_8),
                            URLEncoder.encode(source, StandardCharsets.UTF_8),
                            URLEncoder.encode(sourceId, StandardCharsets.UTF_8),
                            data.toString()
                    );
                    throw new RuntimeException("Got http status code " + response.status() + " with reason: " + response.status().reasonPhrase());
                }
            } else if ("DELETE".equals(method)) {
                WebClientResponse response = Retry.decorateSupplier(retry, () -> {
                    WebClientRequestBuilder builder = webClient.delete();
                    builder.headers().add("Origin", "localhost");
                    builder.skipUriEncoding()
                            .path(path)
                            .queryParam("timestamp", versionStr)
                            .queryParam("source", source)
                            .queryParam("sourceId", sourceId);
                    return builder.submit()
                            .toCompletableFuture()
                            .join();
                }).get();
                if (!Http.ResponseStatus.Family.SUCCESSFUL.equals(response.status().family())) {
                    LOG.warn("Unsuccessful HTTP response while attempting to perform: DELETE {}?timestamp={}&source={}&sourceId={}",
                            path,
                            URLEncoder.encode(versionStr, StandardCharsets.UTF_8),
                            URLEncoder.encode(source, StandardCharsets.UTF_8),
                            URLEncoder.encode(sourceId, StandardCharsets.UTF_8)
                    );
                    throw new RuntimeException("Got http status code " + response.status() + " with reason: " + response.status().reasonPhrase());
                }
            } else {
                throw new RuntimeException("Unsupported method: " + method);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    class Pipe implements Runnable {
        final ZonedDateTime startedAt;

        Pipe() {
            startedAt = ZonedDateTime.now(ZoneOffset.UTC);
        }

        @Override
        public void run() {
            try {
                String topic = config.get("pipe.source.topic").asString().get();
                ULID.Value previousUlid = getLatestSourceIdFromTarget().map(ULID::parseULID).orElse(null);
                try (RawdataConsumer consumer = ((RawdataClient) instanceByType.get(RawdataClient.class)).consumer(topic, previousUlid, false)) {
                    while (waitLoopAllowed.get()) {
                        RawdataMessage message = consumer.receive(3, TimeUnit.SECONDS);
                        if (message != null) {
                            try {
                                sendMessageToTarget(message);
                            } catch (Throwable t) {
                                LOG.debug("While processing/sending rawdata. RawdataMessage: {}", asJson(message));
                                throw t;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                LOG.error("Unexpected error", t);
            } finally {
                instanceByType.remove(Pipe.class);
                instanceByType.remove(Thread.class);
            }
        }
    }

    private ObjectNode asJson(RawdataMessage message) {
        ObjectNode r = msgPackMapper.createObjectNode();
        r.put("ulid", message.ulid().toString());
        r.put("position", message.position());
        r.put("orderingGroup", message.orderingGroup());
        r.put("sequenceNumber", message.sequenceNumber());
        r.put("timestamp", ZonedDateTime.ofInstant(Instant.ofEpochMilli(message.timestamp()), ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
        ObjectNode data = r.putObject("data");
        for (String key : message.keys()) {
            byte[] valueBytes = message.get(key);
            try {
                JsonNode node = msgPackMapper.readTree(valueBytes);
                data.set(key, node);
            } catch (IOException e) {
                data.put(key, valueBytes);
            }
        }
        return r;
    }

    public void close() {
        RawdataClient rawdataClient = (RawdataClient) instanceByType.get(RawdataClient.class);
        if (rawdataClient != null) {
            try {
                rawdataClient.close();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
