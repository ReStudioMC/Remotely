package redxax.oxy.remotely.metadata;

import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.http.HttpRequest;
import restudio.rescreen.platform.http.HttpResponse;
import restudio.rescreen.platform.http.HttpTransport;
import restudio.resync.metadata.MetadataBundleId;
import restudio.resync.metadata.MetadataCoordinate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class MetadataHttpTransport implements MetadataTransport {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final HttpTransport transport;
    private final String baseUrl;

    public MetadataHttpTransport(HttpTransport transport, String baseUrl) {
        this.transport = Objects.requireNonNull(transport, "Metadata HTTP transport is required");
        String checked = Objects.requireNonNull(baseUrl, "Metadata base URL is required").strip();
        if (checked.endsWith("/")) checked = checked.substring(0, checked.length() - 1);
        this.baseUrl = checked;
    }

    @Override
    public Async<ManifestResponse> resolve(MetadataRepository.Request request, String etag) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/resolve?" + query(request))).timeout(TIMEOUT).GET();
        if (etag != null && !etag.isBlank()) builder.header("If-None-Match", etag);
        return transport.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            if (response.statusCode() == 304) {
                return new ManifestResponse(Status.NOT_MODIFIED, response.headers().firstValue("ETag").orElse(etag), new byte[0]);
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Metadata manifest request failed with HTTP " + response.statusCode());
            }
            return new ManifestResponse(Status.RESOLVED, response.headers().firstValue("ETag").orElse(""), response.body());
        });
    }

    @Override
    public Async<byte[]> bundle(MetadataBundleId bundleId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/bundles/" + bundleId.canonicalText()))
            .timeout(TIMEOUT).GET().build();
        return transport.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Metadata bundle request failed with HTTP " + response.statusCode());
            }
            return response.body();
        });
    }

    private static String query(MetadataRepository.Request request) {
        List<String> values = new ArrayList<>();
        request.artifactFamilies().stream().sorted().forEach(family -> add(values, "artifactFamily", family.canonicalText()));
        MetadataCoordinate coordinate = request.coordinate();
        add(values, "edition", coordinate.edition());
        add(values, "minecraftVersion", coordinate.minecraftVersion());
        add(values, "dataVersion", coordinate.dataVersion());
        add(values, "protocolVersion", coordinate.protocolVersion());
        add(values, "softwareFamily", coordinate.softwareFamily());
        add(values, "softwareVersion", coordinate.softwareVersion());
        add(values, "distribution", coordinate.distribution());
        request.capabilities().stream().sorted().forEach(capability -> add(values, "capability", capability));
        return String.join("&", values);
    }

    private static void add(List<String> values, String name, Object value) {
        if (value != null) values.add(encode(name) + "=" + encode(String.valueOf(value)));
    }

    private static String encode(String value) {
        StringBuilder result = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte current : bytes) {
            int unsigned = current & 255;
            if (unsigned >= 'a' && unsigned <= 'z' || unsigned >= 'A' && unsigned <= 'Z'
                || unsigned >= '0' && unsigned <= '9' || unsigned == '-' || unsigned == '_' || unsigned == '.' || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit((unsigned >>> 4) & 15, 16)));
                result.append(Character.toUpperCase(Character.forDigit(unsigned & 15, 16)));
            }
        }
        return result.toString();
    }
}
