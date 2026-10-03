package redxax.oxy.remotely.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkOperationStatus;
import redxax.oxy.remotely.network.protocol.NetworkProtocolCodec;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.util.JsonTreeParser;

import java.net.URLEncoder;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class HostedNetworkClient {
    private static final String ROOT = "/hosted-networks";
    private final Transport transport;
    private final NetworkProtocolCodec protocol = new NetworkProtocolCodec();

    public HostedNetworkClient(Transport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public Async<NetworkOperationStatus> submit(NetworkCommand command) {
        Objects.requireNonNull(command, "command");
        return submit(command, bodyFor(command));
    }

    private Async<NetworkOperationStatus> submit(NetworkCommand command, String body) {
        return transport.request("POST", ROOT + "/commands", body).thenApply(response ->
                status(response, command.networkId(), command.requestId(), command.type()));
    }

    public Async<NetworkOperationStatus> execute(NetworkCommand command, TaskScheduler scheduler, BooleanSupplier current) {
        return executePrepared(command, bodyFor(command), scheduler, current);
    }

    public Async<NetworkOperationStatus> execute(NetworkCommand.Create command, String body, TaskScheduler scheduler, BooleanSupplier current) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(body, "body");
        if (!command.equals(createFromBody(body))) throw new IllegalArgumentException("Pending Network Command Does Not Match");
        return executePrepared(command, body, scheduler, current);
    }

    private Async<NetworkOperationStatus> executePrepared(NetworkCommand command, String body, TaskScheduler scheduler, BooleanSupplier current) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(current, "current");
        Async<NetworkOperationStatus> result = Async.pending();
        if (!current.getAsBoolean()) return Async.failed(new IllegalStateException("Account Changed. Reopen Network Creation"));
        submit(command, body).whenComplete((status, failure) -> observe(command, status, failure, scheduler, current, result, 0));
        return result;
    }

    static String bodyFor(NetworkCommand command) {
        return commandBody(new NetworkProtocolCodec().encode(Objects.requireNonNull(command, "command")));
    }

    static NetworkCommand.Create createFromBody(String body) {
        JsonElement parsed = JsonTreeParser.parse(body);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Pending Network Command Is Invalid");
        Map<String, Object> value = object(parsed.getAsJsonObject());
        convertLong(value, "expectedRevision");
        Object members = value.get("members");
        if (!(members instanceof List<?> list)) throw new IllegalArgumentException("Pending Network Members Are Invalid");
        for (Object member : list) {
            if (!(member instanceof Map<?, ?> entry)) throw new IllegalArgumentException("Pending Network Member Is Invalid");
            Object source = entry.get("source");
            if (!(source instanceof Map<?, ?> sourceMap)) throw new IllegalArgumentException("Pending Network Source Is Invalid");
            if (!"DRAFT".equals(sourceMap.get("kind"))) continue;
            @SuppressWarnings("unchecked") Map<String, Object> draft = (Map<String, Object>) sourceMap;
            convertLong(draft, "expectedRevision");
            convertLong(nested(draft, "installer"), "ramMiB");
            convertLong(nested(draft, "installer"), "cpuQuotaPercent");
            convertLong(nested(draft, "runtime"), "ramMiB");
            convertLong(nested(draft, "runtime"), "cpuQuotaPercent");
            convertLong(nested(draft, "retained"), "diskMiB");
            convertLong(nested(draft, "retained"), "backupMiB");
        }
        NetworkCommand command = new NetworkProtocolCodec().decodeCommand(value);
        if (!(command instanceof NetworkCommand.Create create)) throw new IllegalArgumentException("Pending Network Command Is Not Creation");
        return create;
    }

    private void observe(NetworkCommand command, NetworkOperationStatus status, Throwable failure, TaskScheduler scheduler,
                         BooleanSupplier current, Async<NetworkOperationStatus> result, int polls) {
        if (result.isDone()) return;
        if (!current.getAsBoolean()) {
            result.fail(new IllegalStateException("Account Changed. Reopen Network Creation"));
            return;
        }
        if (failure != null) {
            result.fail(failure);
            return;
        }
        switch (status.state()) {
            case SUCCEEDED -> result.complete(status);
            case FAILED, ROLLED_BACK, NEEDS_REVIEW -> result.fail(new OperationFailure(status));
            default -> {
                if (polls >= 900) {
                    result.fail(new IllegalStateException("Network Creation Is Still Running. Retry To Check The Same Operation"));
                    return;
                }
                try {
                    TaskScheduler.ScheduledTask task = scheduler.schedule(() -> {
                        if (result.isDone()) return;
                        if (!current.getAsBoolean()) {
                            result.fail(new IllegalStateException("Account Changed. Reopen Network Creation"));
                            return;
                        }
                        operation(command.networkId(), UUID.fromString(command.requestId()), command.type())
                                .whenComplete((next, error) -> observe(command, next, error, scheduler, current, result, polls + 1));
                    }, Duration.ofSeconds(2));
                    result.onCancel(task::cancel);
                } catch (RuntimeException error) {
                    result.fail(error);
                }
            }
        }
    }

    public Async<NetworkOperationStatus> operation(String networkId, UUID requestId) {
        return operation(networkId, requestId, null);
    }

    public Async<NetworkOperationStatus> operation(String networkId, UUID requestId, NetworkCommand.Type command) {
        String network = networkId(networkId);
        UUID request = Objects.requireNonNull(requestId, "requestId");
        return transport.request("GET", ROOT + "/" + segment(network) + "/operations/" + request, null)
                .thenApply(response -> status(response, network, request.toString(), command));
    }

    private NetworkOperationStatus status(String response, String networkId, String requestId, NetworkCommand.Type command) {
        JsonElement parsed = JsonTreeParser.parse(response);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Hosted Network Status Is Invalid");
        Map<String, Object> value = object(parsed.getAsJsonObject());
        Object actualCommand = value.get("command");
        if (!(actualCommand instanceof String name)) throw new IllegalArgumentException("Hosted Network Command Is Missing");
        NetworkCommand.Type type;
        try {
            type = NetworkCommand.Type.valueOf(name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Hosted Network Command Is Invalid", failure);
        }
        if (command != null && type != command) throw new IllegalArgumentException("Hosted Network Command Does Not Match");
        statusLongs(value);
        NetworkOperationStatus status = protocol.decodeStatus(value);
        if (!requestId.equals(status.requestId()) || !networkId.equals(status.networkId()) || type != status.command()) {
            throw new IllegalArgumentException("Hosted Network Status Identity Does Not Match");
        }
        if (status.job() != null && !networkId.equals(status.job().networkId())
                || status.lifecycleJob() != null && !networkId.equals(status.lifecycleJob().networkId())) {
            throw new IllegalArgumentException("Hosted Network Job Identity Does Not Match");
        }
        return status;
    }

    private static String commandBody(Map<String, Object> command) {
        JsonObject value = jsonObject(command);
        stringLong(value, "expectedRevision");
        JsonElement members = value.get("members");
        if (members != null && members.isJsonArray()) {
            for (JsonElement member : members.getAsJsonArray()) draftLongs(member);
        }
        draftLongs(value.get("member"));
        return JsonTreeParser.write(value);
    }

    private static void draftLongs(JsonElement member) {
        if (member == null || !member.isJsonObject()) return;
        JsonElement source = member.getAsJsonObject().get("source");
        if (source == null || !source.isJsonObject()) return;
        JsonObject draft = source.getAsJsonObject();
        JsonElement kind = draft.get("kind");
        if (kind == null || !kind.isJsonPrimitive() || !"DRAFT".equals(kind.getAsString())) return;
        stringLong(draft, "expectedRevision");
        computeLongs(draft.get("installer"));
        computeLongs(draft.get("runtime"));
        JsonElement retained = draft.get("retained");
        if (retained != null && retained.isJsonObject()) {
            stringLong(retained.getAsJsonObject(), "diskMiB");
            stringLong(retained.getAsJsonObject(), "backupMiB");
        }
    }

    private static void computeLongs(JsonElement compute) {
        if (compute == null || !compute.isJsonObject()) return;
        stringLong(compute.getAsJsonObject(), "ramMiB");
        stringLong(compute.getAsJsonObject(), "cpuQuotaPercent");
    }

    private static void stringLong(JsonObject value, String key) {
        JsonElement item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Hosted Network " + key + " Is Invalid");
        }
        String number = item.getAsString();
        long parsed = exactLong(number);
        value.addProperty(key, Long.toString(parsed));
    }

    private static void statusLongs(Map<String, Object> value) {
        convertLong(value, "networkRevision");
        convertLong(value, "createdAt");
        convertLong(value, "updatedAt");
        Map<String, Object> job = nested(value, "job");
        if (job != null) {
            convertLong(job, "networkRevision");
            convertLong(job, "createdAt");
            convertLong(job, "updatedAt");
        }
        Map<String, Object> lifecycle = nested(value, "lifecycleJob");
        if (lifecycle != null) {
            convertLong(lifecycle, "networkRevision");
            convertLong(lifecycle, "createdAt");
            convertLong(lifecycle, "updatedAt");
            Object steps = lifecycle.get("steps");
            if (!(steps instanceof List<?> list)) throw new IllegalArgumentException("Hosted Network Lifecycle Steps Are Invalid");
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) throw new IllegalArgumentException("Hosted Network Lifecycle Step Is Invalid");
                @SuppressWarnings("unchecked") Map<String, Object> step = (Map<String, Object>) item;
                convertLong(step, "startedAt");
                convertLong(step, "completedAt");
            }
        }
    }

    private static Map<String, Object> nested(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value == null) return null;
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Hosted Network " + key + " Is Invalid");
        @SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) value;
        return nested;
    }

    private static void convertLong(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String text)) throw new IllegalArgumentException("Hosted Network " + key + " Is Invalid");
        value.put(key, exactLong(text));
    }

    private static long exactLong(String value) {
        if (value == null || !value.matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("Hosted Network Integer Is Invalid");
        }
        try {
            long parsed = Long.parseLong(value);
            if (!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("Hosted Network Integer Is Invalid");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Hosted Network Integer Is Outside Its Range", failure);
        }
    }

    private static JsonObject jsonObject(Map<String, ?> values) {
        JsonObject result = new JsonObject();
        values.forEach((key, value) -> result.add(key, json(value)));
        return result;
    }

    private static JsonElement json(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Map<?, ?> source) {
            JsonObject result = new JsonObject();
            source.forEach((key, item) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("Hosted Network Object Key Is Invalid");
                result.add(name, json(item));
            });
            return result;
        }
        if (value instanceof List<?> items) {
            JsonArray result = new JsonArray();
            items.forEach(item -> result.add(json(item)));
            return result;
        }
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Boolean flag) return new JsonPrimitive(flag);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Hosted Network Number Is Invalid");
            return new JsonPrimitive(number);
        }
        throw new IllegalArgumentException("Hosted Network Value Is Invalid");
    }

    private static Map<String, Object> object(JsonObject value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.entrySet().forEach(entry -> result.put(entry.getKey(), read(entry.getValue())));
        return result;
    }

    private static Object read(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) return object(value.getAsJsonObject());
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) result.add(read(item));
            return result;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) return primitive.getAsString();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return exactLong(primitive.getAsString());
        throw new IllegalArgumentException("Hosted Network Value Is Invalid");
    }

    private static String networkId(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > 255
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Hosted Network Identity Is Invalid");
        }
        return value;
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @FunctionalInterface
    public interface Transport {
        Async<String> request(String method, String path, String body);
    }

    public static final class OperationFailure extends IllegalStateException {
        private final NetworkOperationStatus status;

        public OperationFailure(NetworkOperationStatus status) {
            super(status.message().isBlank() ? "Network Creation Needs Recovery" : status.message());
            this.status = Objects.requireNonNull(status, "status");
        }

        public NetworkOperationStatus status() {
            return status;
        }
    }
}
